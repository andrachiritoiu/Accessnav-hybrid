package com.example.accesnav

import android.Manifest
import android.content.Context
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accesnav.databinding.ActivityNavigationBinding
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
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NavigationActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityNavigationBinding
    private var tts: TextToSpeech? = null
    private var udpSocket: DatagramSocket? = null
    private lateinit var cameraExecutor: ExecutorService
    
    // AI Detectors
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .build()
    )
    
    private var lastAnnouncementTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()
        setupControls()
        startUdpListener()
        
        // Handle Destination from intent
        val destination = intent.getStringExtra("DESTINATION")
        if (!destination.isNullOrBlank()) {
            binding.geminiInstruction.text = "Navigating to: $destination"
        }
        
        speak("Vision AI active. Scanning for signs and obstacles.")
    }

    private fun setupControls() {
        binding.stopNavButton.setOnClickListener {
            handleStop()
            finish()
        }
        binding.muteButton.setOnClickListener {
            tts?.stop()
            Toast.makeText(this, "Guidance Muted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
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
            } catch (exc: Exception) {
                Log.e("Camera", "Binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            // Run Text Recognition
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isNotBlank()) {
                        handleRecognizedText(visionText.text)
                    }
                }
            
            // Run Object Detection in parallel (effectively)
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    for (obj in objects) {
                        for (label in obj.labels) {
                            handleDetectedObject(label.text)
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun handleRecognizedText(text: String) {
        val currentTime = System.currentTimeMillis()
        val upperText = text.uppercase()
        if (currentTime - lastAnnouncementTime < 4000) return

        when {
            upperText.contains("EXIT") -> handleDetection("EXIT SIGN", "Exit detected. Follow the path.", "FORWARD")
            upperText.contains("STAIRS") -> handleDetection("STAIRS", "Stairs detected. Careful.", "STOP")
            upperText.contains("LIFT") || upperText.contains("ELEVATOR") -> handleDetection("LIFT", "Elevator ahead.", "FORWARD")
            upperText.contains("RAMP") -> handleDetection("RAMP", "Accessible ramp found.", "FORWARD")
        }
    }

    private fun handleDetectedObject(label: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < 5000) return

        when (label.lowercase()) {
            "chair", "table", "desk" -> handleDetection("OBSTACLE", "Furniture ahead. Move left.", "LEFT")
            "door" -> handleDetection("DOOR", "Door detected in front.", "FORWARD")
            "person" -> handleDetection("PERSON", "Person ahead. Please be cautious.", "STOP")
            "stairs" -> handleDetection("STAIRS", "Stairs detected. Path restricted.", "STOP")
        }
    }

    private fun handleDetection(badge: String, instruction: String, command: String) {
        lastAnnouncementTime = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.Main) {
            binding.detectionBadge.text = badge
            binding.geminiInstruction.text = instruction
            speak(instruction)
            updateHapticUI(command)
            sendWatchCommand(command)
            vibrateCommand(command)
        }
    }

    private fun updateHapticUI(command: String) {
        binding.hapticDescription.text = "Haptic: $command Pulse"
        when(command) {
            "FORWARD" -> binding.hapticIcon.setImageResource(R.drawable.ic_haptic_forward)
            "LEFT" -> binding.hapticIcon.setImageResource(R.drawable.ic_haptic_forward) // Needs LEFT icon
            "RIGHT" -> binding.hapticIcon.setImageResource(R.drawable.ic_haptic_forward)
            "STOP" -> binding.hapticIcon.setImageResource(R.drawable.ic_volume_up)
        }
    }

    private fun handleStop() {
        speak("Navigation stopped.")
        sendWatchCommand("STOP")
        vibrateCommand("STOP")
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
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        when (type) {
            "FORWARD" -> vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            "LEFT" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1))
            "RIGHT" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1))
            "STOP" -> vibrator.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun startUdpListener() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    udpSocket = DatagramSocket(5050)
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    while (true) {
                        udpSocket?.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim().uppercase()
                        withContext(Dispatchers.Main) {
                            when (message) {
                                "RAMP_RIGHT" -> handleDetection("BEACON", "Ramp detected on right.", "RIGHT")
                                "DANGER" -> handleDetection("BEACON", "Danger zone ahead. Stop.", "STOP")
                            }
                        }
                    }
                } catch (e: Exception) {
                    udpSocket?.close()
                    delay(5000)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        tts?.shutdown()
        udpSocket?.close()
        super.onDestroy()
    }
}
