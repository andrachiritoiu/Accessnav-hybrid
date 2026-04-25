package com.example.accesnav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Location
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accesnav.databinding.ActivityNavigationBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.wearable.Wearable
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NavigationActivity : AppCompatActivity(), OnMapReadyCallback, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityNavigationBinding
    private var tts: TextToSpeech? = null
    private var udpSocket: DatagramSocket? = null
    private lateinit var cameraExecutor: ExecutorService
    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    
    // AI Detectors
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .build()
    )
    
    private var lastAnnouncementTime = 0L
    private val THROTTLE_MS = 2000L
    
    private var isExitingHouse = true
    private val navigationSteps = mutableListOf<String>()
    private var currentStepIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.miniMap) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // Parse Google Steps
        val stepsJson = intent.getStringExtra("STEPS_JSON") ?: ""
        parseSteps(stepsJson)

        startCamera()
        setupControls()
        startUdpListener()
        startLocationUpdates()
        
        binding.geminiInstruction.text = "Searching for Exit..."
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.apply {
                language = Locale.US
                setPitch(1.0f)
                setSpeechRate(0.9f)
                speak("AI Navigation active. Phase 1: Exiting house. Please find the exit door.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun parseSteps(json: String) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val step = array.getJSONObject(i)
                val instruction = step.getString("html_instructions")
                    .replace(Regex("<.*?>"), "") // Strip HTML tags
                navigationSteps.add(instruction)
            }
        } catch (e: Exception) { }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.isZoomControlsEnabled = false
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap?.isMyLocationEnabled = true
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { updateMiniMap(it) }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun updateMiniMap(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
    }

    private fun setupControls() {
        binding.stopNavButton.setOnClickListener { finish() }
        binding.muteButton.setOnClickListener { tts?.stop() }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(imageProxy) }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val imageWidth = image.width
            
            textRecognizer.process(image).addOnSuccessListener { visionText ->
                if (visionText.text.isNotBlank()) analyzeText(visionText.text)
            }
            
            // Run Object Detection
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    for (obj in objects) {
                        val centerX = obj.boundingBox.centerX()
                        if (centerX > imageWidth / 3 && centerX < 2 * imageWidth / 3) {
                            var isPerson = false
                            var topLabel = "Object"
                            
                            for (label in obj.labels) {
                                Log.d("AI_DEBUG", "Detected label: ${label.text}")
                                if (label.text.lowercase().contains("person") || 
                                    label.text.lowercase().contains("human") ||
                                    label.text.lowercase().contains("man") ||
                                    label.text.lowercase().contains("woman")) {
                                    isPerson = true
                                }
                                if (topLabel == "Object") topLabel = label.text
                            }

                            if (isPerson) {
                                handlePersonDetected()
                            } else {
                                handleObstacleDetected(topLabel)
                            }
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun analyzeText(text: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < THROTTLE_MS) return
        
        val upperText = text.uppercase()
        
        // Handle Phase Transition
        if (isExitingHouse && (upperText.contains("EXIT") || upperText.contains("DOOR") || upperText.contains("OUT"))) {
            handleExitReached()
            return
        }

        // Safety Signs & Room Recognition
        when {
            upperText.contains("WET FLOOR") || upperText.contains("CAUTION") -> 
                handleDetection("HAZARD", "Caution: Wet floor or danger ahead. Move slowly.", "STOP")
            
            upperText.contains("STAIRS") || upperText.contains("SCARI") -> 
                handleDetection("STAIRS", "Stairs detected. Watch your step.", "STOP")
            
            upperText.contains("LIFT") || upperText.contains("ELEVATOR") -> 
                handleDetection("LIFT", "Elevator detected ahead.", "FORWARD")

            // Regex for Room/Office detection (e.g. Room 301, Office A)
            upperText.contains("ROOM") || upperText.contains("OFFICE") || upperText.matches(Regex(".*\\b\\d{3}\\b.*")) -> {
                val roomMsg = "Detected: $text"
                handleDetection("ROOM", roomMsg, "FORWARD")
            }
        }
    }

    private fun handleExitReached() {
        isExitingHouse = false
        speak("You have exited the house. Phase 2: Outdoor navigation started.")
        binding.detectionBadge.text = "OUTDOOR"
        
        // Start providing Google Directions
        provideNextGoogleStep()
    }

    private fun provideNextGoogleStep() {
        if (currentStepIndex < navigationSteps.size) {
            val instruction = navigationSteps[currentStepIndex]
            handleDetection("NAV", instruction, getCommandFromInstruction(instruction))
            currentStepIndex++
            
            // For demo, we might auto-trigger next step or wait for location
        } else {
            speak("You have reached your destination.")
        }
    }

    private fun getCommandFromInstruction(text: String): String {
        return when {
            text.lowercase().contains("left") -> "LEFT"
            text.lowercase().contains("right") -> "RIGHT"
            else -> "FORWARD"
        }
    }

    private fun handleObstacleDetected(label: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < THROTTLE_MS) return
        
        handleDetection("OBSTACLE", "$label in path. Move left to avoid it.", "STOP")
    }

    private fun handlePersonDetected() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < THROTTLE_MS) return
        
        handleDetection("PERSON", "Person ahead. Please be careful.", "STOP")
    }

    private fun handleDetection(badge: String, instruction: String, command: String) {
        lastAnnouncementTime = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.Main) {
            binding.detectionBadge.text = badge
            binding.geminiInstruction.text = instruction
            
            // Speak in English
            tts?.speak(instruction, TextToSpeech.QUEUE_FLUSH, null, null)
            
            // Haptics: 1 for LEFT, 2 for RIGHT
            vibrateCommand(command)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun vibrateCommand(type: String) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        
        val pattern = when (type) {
            "LEFT" -> longArrayOf(0, 300) // 1 pulse for Left
            "RIGHT" -> longArrayOf(0, 300, 150, 300) // 2 pulses for Right
            "STOP" -> longArrayOf(0, 800) // Long pulse for stop
            else -> longArrayOf(0, 100) // Short pulse for forward
        }
        
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun startUdpListener() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(5050)
                udpSocket = socket
                val buffer = ByteArray(1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim().uppercase()
                    withContext(Dispatchers.Main) {
                        if (message == "RAMP_RIGHT") handleDetection("BEACON", "Ramp detected.", "RIGHT")
                    }
                }
            } catch (e: Exception) { }
        }
    }


    override fun onDestroy() {
        cameraExecutor.shutdown()
        tts?.shutdown()
        udpSocket?.close()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }
}
