package com.example.accesnav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var tts: TextToSpeech? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val destination = data?.get(0) ?: ""
            handleDestinationSelected(destination)
        }
    }

    private val confirmationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val response = data?.get(0)?.lowercase() ?: ""
            handleConfirmationResponse(response)
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

        // Initialize Maps SDK with latest renderer and error handling
        try {
            MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST) { renderer ->
                when (renderer) {
                    MapsInitializer.Renderer.LATEST -> Log.d("Maps", "The latest version of the renderer is used.")
                    MapsInitializer.Renderer.LEGACY -> Log.d("Maps", "The legacy version of the renderer is used.")
                }
            }
        } catch (e: Exception) {
            Log.e("Maps", "MapsInitializer failed: ${e.message}")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        
        // Find map fragment and load map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        if (mapFragment != null) {
            Log.d("Maps", "Map Fragment found successfully!")
            mapFragment.getMapAsync(this)
        } else {
            Log.e("Maps", "CRITICAL: Map Fragment is NULL!")
            Toast.makeText(this, "Eroare: Fragmentul hărții nu a fost găsit!", Toast.LENGTH_LONG).show()
        }

        setupUI()
        checkApiKey()
    }

    private fun checkApiKey() {
        try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val apiKey = info.metaData.getString("com.google.android.geo.API_KEY")
            if (apiKey == "YOUR_API_KEY_HERE" || apiKey.isNullOrBlank()) {
                binding.apiKeyWarning.visibility = View.VISIBLE
                Toast.makeText(this, "HARTA ESTE BLOCATĂ: Adaugă API KEY în Manifest!", Toast.LENGTH_LONG).show()
            } else {
                binding.apiKeyWarning.visibility = View.GONE
            }
        } catch (e: Exception) {
            binding.apiKeyWarning.visibility = View.VISIBLE
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        Log.d("Maps", "Map is ready and callback received!")
        
        googleMap?.setOnMapLoadedCallback {
            Log.d("Maps", "Map fully loaded")
            Toast.makeText(this, "Harta Google s-a încărcat cu succes!", Toast.LENGTH_SHORT).show()
        }

        // High-contrast accessibility settings
        googleMap?.uiSettings?.isZoomControlsEnabled = true
        googleMap?.uiSettings?.isMyLocationButtonEnabled = true
        
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
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun setupUI() {
        binding.startNavButton.setOnClickListener {
            startNavigation()
        }

        binding.voiceSearchFab.setOnClickListener {
            startVoiceSearch()
        }

        binding.settingsButton.setOnClickListener {
            Toast.makeText(this, "Maps Accessibility Configured", Toast.LENGTH_SHORT).show()
        }

        val ip = getLocalIpAddress()
        binding.lastActivityText.text = "IP: $ip • System Active"
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Where to?")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice Search Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDestinationSelected(destinationName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
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
                        
                        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                            location?.let {
                                val currentLatLng = LatLng(it.latitude, it.longitude)
                                fetchDirections(currentLatLng, destLatLng, destinationName)
                            } ?: run {
                                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(destLatLng, 15f))
                            }
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Location not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Maps", "Error: ${e.message}")
            }
        }
    }

    private fun fetchDirections(origin: LatLng, dest: LatLng, destName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiKey = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData.getString("com.google.android.geo.API_KEY")
                val urlString = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${dest.latitude},${dest.longitude}&mode=walking&key=$apiKey"
                
                val connection = URL(urlString).openConnection() as HttpURLConnection
                val data = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(data)
                
                if (json.getString("status") == "OK") {
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    val overviewPolyline = route.getJSONObject("overview_polyline").getString("points")
                    val legs = route.getJSONArray("legs").getJSONObject(0)
                    val duration = legs.getJSONObject("duration").getString("text")
                    val distance = legs.getJSONObject("distance").getString("text")
                    
                    val path = decodePolyline(overviewPolyline)
                    
                    withContext(Dispatchers.Main) {
                        googleMap?.addPolyline(PolylineOptions()
                            .addAll(path)
                            .color(Color.parseColor("#1A73E8"))
                            .width(18f))
                        
                        val msg = "Traseu găsit spre $destName. Distanță: $distance. Timp estimat: $duration. Porrim călătoria?"
                        binding.lastActivityText.text = "Timp: $duration ($distance)"
                        
                        announceAndAsk(msg)
                        
                        val bounds = LatLngBounds.Builder()
                            .include(origin)
                            .include(dest)
                            .build()
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 250))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Eroare Directions API: ${json.getString("status")}", Toast.LENGTH_LONG).show()
                        drawFallbackRoute(origin, dest) // Fallback to straight line
                    }
                }
            } catch (e: Exception) {
                Log.e("Maps", "Directions error: ${e.message}")
            }
        }
    }

    private fun announceAndAsk(message: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "CONFIRMATION_ASK")
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "CONFIRMATION_ASK") {
                    runOnUiThread { listenForConfirmation() }
                }
            }
            override fun onError(utteranceId: String?) {}
        })
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "CONFIRMATION_ASK")
    }

    private fun listenForConfirmation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Spune DA pentru a porni sau NU pentru a aștepta")
        }
        try {
            confirmationLauncher.launch(intent)
        } catch (e: Exception) { }
    }

    private fun handleConfirmationResponse(response: String) {
        when {
            response.contains("da") || response.contains("yes") || response.contains("start") -> {
                cancelRetryTimer()
                startNavigation()
            }
            response.contains("nu") || response.contains("no") -> {
                Toast.makeText(this, "Navigare amânată. Voi întreba din nou în 5 minute.", Toast.LENGTH_SHORT).show()
                startRetryTimer()
            }
            else -> {
                tts?.speak("Nu am înțeles. Te rog spune DA sau NU.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun startRetryTimer() {
        cancelRetryTimer()
        retryRunnable = Runnable {
            announceAndAsk("Au trecut 5 minute. Porrim călătoria acum?")
            startRetryTimer() // Reschedule
        }
        retryRunnable?.let { handler.postDelayed(it, 5 * 60 * 1000) }
    }

    private fun cancelRetryTimer() {
        retryRunnable?.let { handler.removeCallbacks(it) }
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
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            poly.add(LatLng(lat.toDouble() / 1e5, lng.toDouble() / 1e5))
        }
        return poly
    }

    private fun drawFallbackRoute(start: LatLng, end: LatLng) {
        googleMap?.addPolyline(PolylineOptions().add(start).add(end).color(Color.GRAY).width(12f))
    }

    private fun startNavigation() {
        val intent = Intent(this, NavigationActivity::class.java)
        intent.putExtra("DESTINATION", binding.destinationText.text.toString())
        startActivity(intent)
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
        cancelRetryTimer()
        tts?.shutdown()
        super.onDestroy()
    }
}