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
import java.net.NetworkInterface
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var tts: TextToSpeech? = null

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
                                drawAccessibleRoute(currentLatLng, destLatLng)
                                
                                val results = FloatArray(1)
                                android.location.Location.distanceBetween(
                                    it.latitude, it.longitude,
                                    address.latitude, address.longitude,
                                    results
                                )
                                val distance = results[0]
                                val minutes = (distance / 75).toInt() + 1
                                
                                val msg = "Traseu configurat. Timp estimat: $minutes minute."
                                binding.lastActivityText.text = "Est. time: $minutes min"
                                tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
                                
                                val bounds = LatLngBounds.Builder()
                                    .include(currentLatLng)
                                    .include(destLatLng)
                                    .build()
                                googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 250))
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

    private fun drawAccessibleRoute(start: LatLng, end: LatLng) {
        val polylineOptions = PolylineOptions()
            .add(start)
            .add(end)
            .color(Color.parseColor("#1A73E8"))
            .width(18f)
            .geodesic(true)
        googleMap?.addPolyline(polylineOptions)
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
        tts?.shutdown()
        super.onDestroy()
    }
}