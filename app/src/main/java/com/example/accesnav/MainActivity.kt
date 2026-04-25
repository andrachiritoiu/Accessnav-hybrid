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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupUI()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        enableMyLocation()
        
        googleMap?.uiSettings?.isMyLocationButtonEnabled = true
        googleMap?.uiSettings?.isCompassEnabled = true
        
        // Check for API Key
        try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val apiKey = info.metaData.getString("com.google.android.geo.API_KEY")
            if (apiKey == "YOUR_API_KEY_HERE") {
                Toast.makeText(this, "IMPORTANT: Please set your Google Maps API Key in AndroidManifest.xml", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { }
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
            Toast.makeText(this, "Settings Ready", Toast.LENGTH_SHORT).show()
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
        } catch (e: Exception) { }
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
                        
                        // Calculate route and time
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                location?.let {
                                    val currentLatLng = LatLng(it.latitude, it.longitude)
                                    drawAccessibleRoute(currentLatLng, destLatLng)
                                    
                                    // Distance/Time Calculation
                                    val results = FloatArray(1)
                                    android.location.Location.distanceBetween(
                                        it.latitude, it.longitude,
                                        address.latitude, address.longitude,
                                        results
                                    )
                                    val distance = results[0]
                                    val minutes = (distance / 75).toInt() + 1 // ~75m/min walking
                                    
                                    val msg = "Traseu configurat către $destinationName. Timp estimat: $minutes minute."
                                    binding.lastActivityText.text = "Est. time: $minutes min"
                                    tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
                                    
                                    // Zoom to fit both
                                    val bounds = LatLngBounds.Builder()
                                        .include(currentLatLng)
                                        .include(destLatLng)
                                        .build()
                                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 200))
                                } ?: run {
                                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(destLatLng, 15f))
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Could not find destination", Toast.LENGTH_SHORT).show()
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