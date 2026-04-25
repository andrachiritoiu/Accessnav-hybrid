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
import java.net.URL
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var udpStarted = false
    
    // Voice Flow States
    private enum class VoiceState { IDLE, ASKING_LOCATION, ASKING_DESTINATION }
    private var currentVoiceState = VoiceState.IDLE

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val response = data?.get(0)?.lowercase() ?: ""
            handleVoiceResponse(response)
        }
    }

    private fun handleVoiceResponse(response: String) {
        when (currentVoiceState) {
            VoiceState.ASKING_LOCATION -> {
                if (response.contains("indoor")) {
                    currentVoiceState = VoiceState.IDLE
                    tts?.speak("Starting Indoor Mode.", TextToSpeech.QUEUE_FLUSH, null, null)
                    startIndoorNavigation()
                } else if (response.contains("outdoor")) {
                    currentVoiceState = VoiceState.ASKING_DESTINATION
                    tts?.speak("Outdoor Mode active. Where would you like to go?", TextToSpeech.QUEUE_FLUSH, null, "READY")
                } else {
                    tts?.speak("I didn't catch that. Please say indoors or outdoors.", TextToSpeech.QUEUE_FLUSH, null, "READY")
                }
            }
            VoiceState.ASKING_DESTINATION -> {
                currentVoiceState = VoiceState.IDLE
                handleDestinationSelected(response)
            }
            else -> {}
        }
    }

    private fun startIndoorNavigation() {
        val intent = Intent(this, NavigationActivity::class.java)
        intent.putExtra("START_OUTDOOR", false)
        startActivity(intent)
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
                    
                    val welcomeMsg = "Welcome to Access Nav. Are you indoors or outdoors?"
                    currentVoiceState = VoiceState.ASKING_LOCATION
                    speak(welcomeMsg)
                }
            }
        }
        
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        setupUI()
        checkApiKey()
        startUdpListener()
    }

    private fun checkApiKey() {
        try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val apiKey = info.metaData.getString("com.google.android.geo.API_KEY")
            if (apiKey == "YOUR_API_KEY_HERE" || (apiKey.isNullOrBlank())) {
                Toast.makeText(this, "⚠️ API KEY MISSING", Toast.LENGTH_LONG).show()
                mainHandler.postDelayed({
                    tts?.speak("Warning: Google Maps API key is missing. Navigation will not work correctly.", TextToSpeech.QUEUE_ADD, null, null)
                }, 2000)
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
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    private fun setupUI() {
        binding.emergencyStop.setOnClickListener { 
            tts?.stop()
            speak("Emergency stop activated. All guidance paused.")
            saveHistory("SYSTEM", "Emergency stop triggered")
        }
        
        setupBottomNavigation()
    }

    private fun saveHistory(type: String, content: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = com.example.accesnav.db.AppDatabase.getDatabase(this@MainActivity)
                db.historyDao().insert(com.example.accesnav.db.HistoryItem(type = type, content = content))
            } catch (e: Exception) { }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_camera -> {
                    startActivity(Intent(this, NavigationActivity::class.java))
                    true
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                else -> true
            }
        }
    }

    private fun startVoiceSearch() {
        val prompt = when (currentVoiceState) {
            VoiceState.ASKING_LOCATION -> "Indoors or Outdoors?"
            VoiceState.ASKING_DESTINATION -> "Where to?"
            else -> "I'm listening"
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
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
                        binding.guidanceText.text = "Target: $destinationName"
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
                        val msg = "Route found. Distance is $distance, time is $duration. Starting navigation."
                        binding.lastActivityText.text = "$duration ($distance)"
                        speak(msg)
                        
                        val bounds = LatLngBounds.Builder().include(origin).include(dest).build()
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 250))

                        // Transition to NavigationActivity for outdoor mode
                        mainHandler.postDelayed({
                            val intent = Intent(this@MainActivity, NavigationActivity::class.java)
                            intent.putExtra("DESTINATION", "Target Location")
                            intent.putExtra("STEPS_JSON", stepsJson)
                            intent.putExtra("START_OUTDOOR", true)
                            startActivity(intent)
                        }, 3000)
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun startUdpListener() {
        if (udpStarted) return
        udpStarted = true
        
        thread(start = true) {
            try {
                val socket = DatagramSocket(5050)
                while (true) {
                    val buffer = ByteArray(1024)
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
        val (status, speech) = when (message) {
            "RAMP_RIGHT" -> "Accessible ramp detected on the right." to "Accessible ramp detected on the right."
            "STAIRS_AHEAD", "STAIRS" -> "Stairs ahead. Path may not be accessible." to "Stairs ahead. This path may not be accessible."
            "LOW_LIGHT" -> "Low visibility area. Proceed with caution." to "Low visibility area detected. Proceed with caution."
            "ACCESSIBLE_EXIT_RIGHT" -> "Accessible exit on your right." to "Accessible exit on your right."
            "STOP" -> "Stop. Unsafe path ahead." to "Stop. Unsafe path ahead."
            else -> "Beacon: $message" to "Building update received."
        }

        binding.lastActivityText.text = status
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, null)
        saveHistory("BEACON", status)
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
            lat += if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            poly.add(LatLng(lat.toDouble() / 1e5, lng.toDouble() / 1e5))
        }
        return poly
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "READY")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}