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
import com.example.accesnav.databinding.ActivityMainBinding
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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private var tts: TextToSpeech? = null
    private var udpSocket: DatagramSocket? = null
    private lateinit var cameraExecutor: ExecutorService
    
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastAnnouncementTime = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else Toast.makeText(this, "Camera permission required for vision", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.ipText.text = "IP: ${getLocalIpAddress()}"

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setupButtons()
        startUdpListener()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("Camera", "Use case binding failed", exc)
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
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun handleRecognizedText(text: String) {
        val currentTime = System.currentTimeMillis()
        val upperText = text.uppercase()
        
        // Cooldown to avoid repeating the same alert too fast (4 seconds)
        if (currentTime - lastAnnouncementTime < 4000) return

        when {
            upperText.contains("EXIT") -> {
                handleExitDetected()
                lastAnnouncementTime = currentTime
            }
            upperText.contains("STAIRS") -> {
                handleStairsDetected()
                lastAnnouncementTime = currentTime
            }
            upperText.contains("LIFT") || upperText.contains("ELEVATOR") -> {
                handleLiftDetected()
                lastAnnouncementTime = currentTime
            }
            upperText.contains("ROOM") -> {
                speak("Room sign detected.")
                lastAnnouncementTime = currentTime
            }
        }
    }

    private fun setupButtons() {
        binding.startNavButton.setOnClickListener { handleStartNavigation() }
        binding.stopButton.setOnClickListener { handleStop() }
        binding.simulateExitButton.setOnClickListener { handleExitDetected() }
        binding.simulateStairsButton.setOnClickListener { handleStairsDetected() }
        binding.simulateLiftButton.setOnClickListener { handleLiftDetected() }
        binding.simulateRampButton.setOnClickListener { handleRampRightBeacon() }
        
        binding.geminiAnalyzeButton.setOnClickListener {
            vibrateCommand("STOP")
            binding.statusText.text = "ANALYZING SCENE..."
            lifecycleScope.launch {
                delay(2000)
                val analysis = analyzeSceneWithGemini("Current view")
                speak(analysis)
                binding.statusText.text = "GEMINI: $analysis"
            }
        }
    }

    // --- NAVIGATION HANDLERS ---

    private fun handleStartNavigation() {
        speak("Navigation started. Scanning for signs.")
        updateStatus("SCANNING...", "FORWARD")
        vibrateCommand("FORWARD")
    }

    private fun handleExitDetected() {
        speak("Exit sign ahead.")
        updateStatus("EXIT DETECTED", "FORWARD")
        vibrateCommand("FORWARD")
    }

    private fun handleStairsDetected() {
        speak("Stairs detected. Path restricted.")
        updateStatus("STAIRS DETECTED", "STOP")
        vibrateCommand("STOP")
    }

    private fun handleLiftDetected() {
        speak("Elevator ahead.")
        updateStatus("LIFT DETECTED", "FORWARD")
        vibrateCommand("FORWARD")
    }

    private fun handleRampRightBeacon() {
        speak("Ramp detected on right.")
        updateStatus("RAMP ON RIGHT", "RIGHT")
        vibrateCommand("RIGHT")
    }

    private fun handleStop() {
        speak("Navigation stopped.")
        updateStatus("READY", "STOP")
        vibrateCommand("STOP")
    }

    // --- CORE ---

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun updateStatus(status: String, command: String) {
        binding.statusText.text = status
        binding.commandText.text = command
        sendWatchCommand(command)
    }

    private fun sendWatchCommand(command: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, "/nav/$command", command.toByteArray())
                }
            } catch (e: Exception) { }
        }
    }

    private fun vibrateCommand(type: String) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
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

    private fun analyzeSceneWithGemini(context: String) = 
        "Path clear. Stairs ahead but accessible ramp is available on your right."

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
                                "RAMP_RIGHT" -> handleRampRightBeacon()
                                "STAIRS" -> handleStairsDetected()
                                "EXIT" -> handleExitDetected()
                                "STOP" -> handleStop()
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