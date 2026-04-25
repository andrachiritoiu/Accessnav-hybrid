package com.example.accesnav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.accesnav.databinding.ActivityMainBinding
import java.net.NetworkInterface
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val destination = data?.get(0) ?: ""
            handleDestinationSelected(destination)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startNavigation()
        } else {
            Toast.makeText(this, "Camera permission is essential for vision", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.startNavButton.setOnClickListener {
            checkPermissionsAndStart()
        }

        binding.voiceSearchFab.setOnClickListener {
            startVoiceSearch()
        }

        binding.settingsButton.setOnClickListener {
            Toast.makeText(this, "Accessibility Settings Ready", Toast.LENGTH_SHORT).show()
        }

        val ip = getLocalIpAddress()
        binding.lastActivityText.text = "AccessNav Engine Online. IP: $ip"
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Where would you like to go?")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDestinationSelected(destination: String) {
        binding.destinationCard.visibility = View.VISIBLE
        binding.destinationText.text = "Target: $destination"
        
        // --- GOOGLE MAPS API INTEGRATION STUB ---
        // In a production app, we would:
        // 1. Call Google Places API to get Lat/Lng
        // 2. Call Google Directions API with "wheelchair=true" or "walking"
        // 3. Feed the polyline steps into our NavigationActivity
        
        Toast.makeText(this, "Accessible route calculated via Google Maps", Toast.LENGTH_LONG).show()
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startNavigation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startNavigation() {
        val intent = Intent(this, NavigationActivity::class.java)
        // Pass destination if selected
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
}