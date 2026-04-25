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
    private val THROTTLE_MS = 2000L // Requirement 6: Max one alert every 2 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.miniMap) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        startCamera()
        setupControls()
        startUdpListener()
        startLocationUpdates()
        
        val destination = intent.getStringExtra("DESTINATION") ?: "Unknown"
        binding.geminiInstruction.text = "Navigating to: $destination"
        
        speak("Vision navigation started. Looking for obstacles and signs.")
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.apply {
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
        }
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
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
            
            // Run Text Recognition
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isNotBlank()) {
                        analyzeText(visionText.text)
                    }
                }
            
            // Run Object Detection
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    for (obj in objects) {
                        // Requirement 10: Center third detection
                        val centerX = obj.boundingBox.centerX()
                        if (centerX > imageWidth / 3 && centerX < 2 * imageWidth / 3) {
                            handleObstacleDetected(obj.labels.firstOrNull()?.text ?: "Object")
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
        when {
            upperText.contains("EXIT") -> handleExitDetected()
            upperText.contains("STAIRS") || upperText.contains("SCARI") -> handleStairsDetected()
            upperText.contains("LIFT") || upperText.contains("ELEVATOR") -> handleLiftDetected()
            upperText.contains("ROOM") -> handleDetection("ROOM", "Room number detected.", "FORWARD")
        }
    }

    private fun handleExitDetected() {
        handleDetection("EXIT", "Exit sign detected ahead.", "FORWARD")
    }

    private fun handleStairsDetected() {
        handleDetection("STAIRS", "Caution: Stairs detected.", "STOP")
    }

    private fun handleLiftDetected() {
        handleDetection("LIFT", "Elevator or lift detected.", "FORWARD")
    }

    private fun handleObstacleDetected(label: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < THROTTLE_MS) return
        handleDetection("OBSTACLE", "$label detected in your path.", "STOP")
    }

    private fun handleDetection(badge: String, instruction: String, command: String) {
        lastAnnouncementTime = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.Main) {
            binding.detectionBadge.text = badge
            binding.geminiInstruction.text = instruction
            speak(instruction)
            sendWatchCommand(command)
            vibrateCommand(command)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun sendWatchCommand(command: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@NavigationActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@NavigationActivity)
                        .sendMessage(node.id, "/nav/$command", command.toByteArray())
                }
            } catch (e: Exception) { }
        }
    }

    private fun vibrateCommand(type: String) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        
        vibrator?.let {
            when (type) {
                "FORWARD" -> it.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                "STOP" -> it.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
                else -> it.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            }
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
                        when (message) {
                            "RAMP_RIGHT" -> handleDetection("BEACON", "Ramp detected on the right.", "RIGHT")
                            "DANGER" -> handleDetection("BEACON", "Danger ahead. Stop.", "STOP")
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(0.9f)
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
