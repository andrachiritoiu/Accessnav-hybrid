package com.example.accesnav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accesnav.databinding.ActivityNavigationBinding
import com.google.android.gms.wearable.Wearable
import com.google.mlkit.vision.common.InputImage
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
    
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
        
        // Initial feedback
        speak("Navigation active. Scanning for your safety.")
    }

    private fun setupControls() {
        binding.stopNavButton.setOnClickListener {
            handleStop()
            finish() // Return to Home
        }
        
        binding.muteButton.setOnClickListener {
            tts?.stop()
            Toast.makeText(this, "Audio Muted", Toast.LENGTH_SHORT).show()
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
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isNotBlank()) {
                        handleRecognizedText(visionText.text)
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
            upperText.contains("EXIT") -> handleDetection("EXIT DETECTED", "Exit sign ahead. Keep moving forward.", "FORWARD")
            upperText.contains("STAIRS") -> handleDetection("STAIRS DETECTED", "Stairs detected. Path restricted.", "STOP")
            upperText.contains("LIFT") || upperText.contains("ELEVATOR") -> handleDetection("LIFT DETECTED", "Elevator ahead.", "FORWARD")
            upperText.contains("ROOM") -> {
                speak("Room detected.")
                lastAnnouncementTime = currentTime
            }
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
            "STOP" -> binding.hapticIcon.setImageResource(R.drawable.ic_volume_up) // Placeholder for stop icon
            "RIGHT" -> binding.hapticIcon.setImageResource(R.drawable.ic_haptic_forward) // Needs a right icon
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
            "STOP" -> vibrator.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
            "RIGHT" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1))
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
                                "RAMP_RIGHT" -> handleDetection("RAMP DETECTED", "Accessible ramp on your right.", "RIGHT")
                                "STAIRS" -> handleDetection("STAIRS DETECTED", "Stairs detected via beacon.", "STOP")
                                "EXIT" -> handleDetection("EXIT DETECTED", "Exit path confirmed.", "FORWARD")
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
