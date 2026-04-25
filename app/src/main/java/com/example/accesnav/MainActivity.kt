package com.example.accesnav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accesnav.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var udpStarted = false

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val destination = data?.get(0) ?: ""
            handleDestinationSelected(destination)
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            enableMyLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST) { }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.apply {
                    language = Locale.US
                    setPitch(1.0f)
                    setSpeechRate(0.9f)
                    setupTTSListener()
                    speak("Welcome to Access Nav. I am ready. Please say your destination.", TextToSpeech.QUEUE_FLUSH, null, "READY")
                }
            }
        }
        
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        setupUI()
        checkApiKey()
    }

    private fun checkApiKey() {
        try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val apiKey = info.metaData.getString("com.google.android.geo.API_KEY")
            if (apiKey == "YOUR_API_KEY_HERE" || apiKey.isNullOrBlank()) {
                binding.apiKeyWarning.visibility = View.VISIBLE
            }
        } catch (e: Exception) { }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.isZoomControlsEnabled = true
        enableMyLocation()
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap?.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val currentLatLng = LatLng(it.latitude, it.longitude)
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                }
            }
        } else {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun setupTTSListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "READY") {
                    runOnUiThread { startVoiceSearch() }
                }
            }
            override fun onError(utteranceId: String?) {}
        })
    }

    private fun setupUI() {
        binding.startNavButton.setOnClickListener { startNavigation() }
        binding.settingsButton.setOnClickListener { Toast.makeText(this, "Settings ready", Toast.LENGTH_SHORT).show() }
        
        binding.confirmationButton.setOnClickListener {
            cancelTimeout()
            startNavigation()
        }

        val ip = getLocalIpAddress()
        binding.lastActivityText.text = "IP: $ip • System Active"
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Where would you like to go?")
        }
        try { voiceLauncher.launch(intent) } catch (e: Exception) { }
    }

    private fun handleDestinationSelected(destinationName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MainActivity, Locale.US)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(destinationName, 1)
                
                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val destLatLng = LatLng(address.latitude, address.longitude)
                        binding.destinationText.visibility = View.VISIBLE
                        binding.destinationText.text = "Target: $destinationName"
                        googleMap?.clear()
                        googleMap?.addMarker(MarkerOptions().position(destLatLng).title(destinationName))
                        
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                location?.let {
                                    val currentLatLng = LatLng(it.latitude, it.longitude)
                                    fetchDirections(currentLatLng, destLatLng)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun fetchDirections(origin: LatLng, dest: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiKey = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData.getString("com.google.android.geo.API_KEY")
                val urlString = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${dest.latitude},${dest.longitude}&mode=walking&key=$apiKey"
                val connection = URL(urlString).openConnection() as HttpURLConnection
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                
                if (json.getString("status") == "OK") {
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    val overviewPolyline = route.getJSONObject("overview_polyline").getString("points")
                    val legs = route.getJSONArray("legs").getJSONObject(0)
                    val duration = legs.getJSONObject("duration").getString("text")
                    val distance = legs.getJSONObject("distance").getString("text")
                    val stepsJson = legs.getJSONArray("steps").toString()
                    val path = decodePolyline(overviewPolyline)
                    
                    withContext(Dispatchers.Main) {
                        googleMap?.addPolyline(PolylineOptions().addAll(path).color(Color.parseColor("#1A73E8")).width(18f))
                        val msg = "I have found a route. The distance is $distance, and the travel time is $duration. Please double tap the screen if you would like to start the navigation now."
                        binding.lastActivityText.text = "$duration ($distance)"
                        
                        showConfirmationUI(msg, stepsJson)
                        
                        val bounds = LatLngBounds.Builder().include(origin).include(dest).build()
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 250))
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun showConfirmationUI(message: String, stepsJson: String = "") {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "CONFIRM")
        binding.confirmationButton.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.GONE
        binding.topOverlay.visibility = View.GONE
        
        binding.confirmationButton.setTag(R.id.confirmationButton, stepsJson)
        startTimeout()
    }

    private fun startTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable { resetUI() }
        mainHandler.postDelayed(timeoutRunnable!!, 60000)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun resetUI() {
        binding.confirmationButton.visibility = View.GONE
        binding.bottomControls.visibility = View.VISIBLE
        binding.topOverlay.visibility = View.VISIBLE
        binding.destinationText.visibility = View.GONE
        googleMap?.clear()
        binding.lastActivityText.text = "System Ready"
        tts?.speak("Request timed out. Returning to the main screen.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun startUdpListener() {
        if (udpStarted) return
        udpStarted = true
        
        thread(start = true) {
            try {
                val socket = DatagramSocket(5050)
                val buffer = ByteArray(1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()
                    runOnUiThread { handleBeaconMessage(message) }
                }
            } catch (e: Exception) {
                udpStarted = false
            }
        }
    }

    private fun handleBeaconMessage(message: String) {
        val (status, speech, command) = when (message) {
            "RAMP_RIGHT" -> Triple("Accessible ramp detected on the right.", "Accessible ramp detected on the right.", "RIGHT")
            "STAIRS_AHEAD", "STAIRS" -> Triple("Stairs ahead. Path may not be accessible.", "Stairs ahead. This path may not be accessible.", "STOP")
            "LOW_LIGHT" -> Triple("Low visibility area. Proceed with caution.", "Low visibility area detected. Proceed with caution.", "FORWARD")
            "ACCESSIBLE_EXIT_RIGHT" -> Triple("Accessible exit on your right.", "Accessible exit on your right.", "RIGHT")
            "STOP" -> Triple("Stop. Unsafe path ahead.", "Stop. Unsafe path ahead.", "STOP")
            else -> Triple("Beacon: $message", "Building update received.", "FORWARD")
        }

        binding.lastActivityText.text = status
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, null)
        vibrateCommand(command)
    }

    private fun vibrateCommand(command: String) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        val pattern = when (command) {
            "LEFT" -> longArrayOf(0, 300) // 1 pulse for Left
            "RIGHT" -> longArrayOf(0, 300, 150, 300) // 2 pulses for Right
            "STOP" -> longArrayOf(0, 700) // Long pulse for stop
            else -> longArrayOf(0, 150) // Short pulse for forward
        }
        
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            poly.add(LatLng(lat.toDouble() / 1e5, lng.toDouble() / 1e5))
        }
        return poly
    }

    private fun startNavigation() {
        val stepsJson = binding.confirmationButton.getTag(R.id.confirmationButton) as? String ?: ""
        val intent = Intent(this, NavigationActivity::class.java)
        intent.putExtra("DESTINATION", binding.destinationText.text.toString())
        intent.putExtra("STEPS_JSON", stepsJson)
        intent.putExtra("START_OUTDOOR", binding.modeSwitch.isChecked)
        startActivity(intent)
        resetUI()
        
        startUdpListener()
        tts?.speak("AccessNav started. Monitoring smart building beacons.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress ?: "---"
                    }
                }
            }
        } catch (ex: Exception) { }
        return "---"
    }

    override fun onDestroy() {
        cancelTimeout()
        tts?.shutdown()
        super.onDestroy()
    }
}