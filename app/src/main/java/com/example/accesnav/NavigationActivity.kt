package com.example.accesnav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accesnav.databinding.ActivityNavigationBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.wearable.Wearable
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NavigationActivity : AppCompatActivity(), OnMapReadyCallback, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityNavigationBinding
    private var tts: TextToSpeech? = null
    private var udpSocket: DatagramSocket? = null
    private var udpStarted = false
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
    
    private val IGNORED_LABELS = setOf(
        "floor", "ground", "road", "street", "wall", "ceiling", "sky", 
        "outdoor", "indoor", "room", "building", "home", "house", "tree", "plant"
    )

    private val detectionPersistence = mutableMapOf<String, Int>()
    private val PERSISTENCE_THRESHOLD = 2 // Internal ML Kit tracking handles most of this now
    
    private var lastProcessTime = 0L
    private val FRAME_INTERVAL_MS = 150L // Limit to ~7 FPS
    
    private lateinit var pipeline: DetectionPipeline
    private lateinit var wearService: WearService
    
    private var lastAnnouncementTime = 0L
    private val THROTTLE_MS = 2000L
    
    private var isExitingHouse = true
    private val navigationSteps = mutableListOf<String>()
    private var currentStepIndex = 0
    private var isAssistanceEnabled = true
    
    private fun saveHistory(type: String, content: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = com.example.accesnav.db.AppDatabase.getDatabase(this@NavigationActivity)
                db.historyDao().insert(com.example.accesnav.db.HistoryItem(type = type, content = content))
            } catch (e: Exception) { }
        }
    }

    // Voice Flow States
    private enum class VoiceState { IDLE, ASKING_SWITCH, ASKING_DESTINATION }
    private var currentVoiceState = VoiceState.IDLE
    private var timeoutRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val response = data?.get(0)?.lowercase() ?: ""
            handleVoiceResponse(response)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.miniMap) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        wearService = WearService(this)
        // Pipeline will be initialized once we have image dimensions

        // Parse Google Steps
        val stepsJson = intent.getStringExtra("STEPS_JSON") ?: ""
        parseSteps(stepsJson)

        startCamera()
        setupControls()
        startUdpListener()
        startLocationUpdates()
        
        val startOutdoor = intent.getBooleanExtra("START_OUTDOOR", false)
        if (startOutdoor) {
            handleExitReached()
        } else {
            binding.geminiInstruction.text = "Searching for Exit..."
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.apply {
                language = Locale.US
                setPitch(1.0f)
                setSpeechRate(0.9f)
                setupTTSListener()
                
                val startOutdoor = intent.getBooleanExtra("START_OUTDOOR", false)
                val msg = if (startOutdoor) "Outdoor mode active. Following Google directions." 
                          else "AI Navigation active. Phase 1: Exiting house. Please find the exit door."
                speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun setupTTSListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "READY_VOICE") {
                    runOnUiThread { startVoiceSearch() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    private fun startVoiceSearch() {
        val prompt = when (currentVoiceState) {
            VoiceState.ASKING_SWITCH -> "Switch to Outdoor Mode? Say Yes or No."
            VoiceState.ASKING_DESTINATION -> "Where would you like to go?"
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

    private fun handleVoiceResponse(response: String) {
        when (currentVoiceState) {
            VoiceState.ASKING_SWITCH -> {
                if (response.contains("yes")) {
                    currentVoiceState = VoiceState.ASKING_DESTINATION
                    tts?.speak("Outdoor Mode active. Where would you like to go?", TextToSpeech.QUEUE_FLUSH, null, "READY_VOICE")
                } else {
                    currentVoiceState = VoiceState.IDLE
                    tts?.speak("Continuing Indoor Mode.", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
            VoiceState.ASKING_DESTINATION -> {
                currentVoiceState = VoiceState.IDLE
                handleDestinationSelected(response)
            }
            else -> {}
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
        binding.muteButton.setOnClickListener { 
            isAssistanceEnabled = !isAssistanceEnabled
            val status = if (isAssistanceEnabled) "Assistance Enabled" else "Assistance Muted"
            binding.muteButton.contentDescription = status
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
            if (!isAssistanceEnabled) {
                tts?.stop()
            }
        }
        
        binding.confirmationButton.setOnClickListener {
            cancelTimeout()
            binding.confirmationButton.visibility = View.GONE
            binding.bottomSheet.visibility = View.VISIBLE
            speak("Route confirmed. Starting navigation.")
            provideNextGoogleStep()
        }

        binding.modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            vibrateCommand("FORWARD") // Short vibration for feedback
            if (isChecked) {
                // Outdoor Mode
                isExitingHouse = false
                binding.modeLabel.text = "OUTDOOR MODE"
                binding.modeSwitch.contentDescription = "Outdoor Mode Active"
                speak("Switched to Outdoor Mode. GPS navigation active.")
                provideNextGoogleStep()
            } else {
                // Indoor Mode
                isExitingHouse = true
                binding.modeLabel.text = "INDOOR MODE"
                binding.modeSwitch.contentDescription = "Indoor Mode Active"
                speak("Switched to Indoor Mode. Scanning for exits and obstacles.")
                binding.detectionBadge.text = "SEARCHING"
            }
        }
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
        val currentTime = System.currentTimeMillis()
        if ((currentTime - lastProcessTime) < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            // Initialize pipeline once
            if (!::pipeline.isInitialized) {
                pipeline = DetectionPipeline(image.width, image.height)
            }
            
            // OCR (Parallel)
            textRecognizer.process(image).addOnSuccessListener { visionText ->
                if (visionText.text.isNotBlank()) {
                    val found = analyzeText(visionText.text)
                    if (found) wearService.sendHapticSignal(WearService.PATH_TEXT)
                }
            }
            
            // Object Detection
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    val results = pipeline.processObjects(objects)
                    for (result in results) {
                        handlePipelineDecision(result)
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun handlePipelineDecision(result: DetectionPipeline.DetectionResult) {
        if (!isAssistanceEnabled) return
        
        val path = when (result.decision) {
            DetectionPipeline.Decision.OBSTACLE_CLOSE -> WearService.PATH_LONG
            DetectionPipeline.Decision.OBSTACLE_AHEAD -> WearService.PATH_DANGER
            DetectionPipeline.Decision.OBSTACLE_LEFT -> WearService.PATH_LEFT
            DetectionPipeline.Decision.OBSTACLE_RIGHT -> WearService.PATH_RIGHT
            DetectionPipeline.Decision.PERSON_AHEAD -> WearService.PATH_SHORT
            else -> WearService.PATH_SHORT
        }
        
        wearService.sendHapticSignal(path)
        
        // Visual/Voice feedback
        val msg = "${result.label} ${result.decision.name.lowercase().replace("_", " ")}"
        handleDetection("AI", msg, result.decision.name)
        saveHistory("DETECTION", msg)
    }

    private fun analyzeText(text: String): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < THROTTLE_MS) return false
        
        val upperText = text.uppercase()
        var matched = false
        
        // Handle Phase Transition
        if (isExitingHouse && (upperText.contains("EXIT") || upperText.contains("DOOR") || upperText.contains("OUT"))) {
            handleExitReached()
            return true
        }

        // Safety Signs & Room Recognition
        when {
            upperText.contains("WET FLOOR") || upperText.contains("CAUTION") -> 
                handleDetection("HAZARD", "Caution: Wet floor or danger ahead. Move slowly.", "STOP")
            
            upperText.contains("STAIRS") || upperText.contains("SCARI") -> 
                handleDetection("STAIRS", "Stairs detected. Watch your step.", "STOP")
            
            upperText.contains("LIFT") || upperText.contains("ELEVATOR") -> 
                handleDetection("LIFT", "Elevator detected ahead.", "FORWARD")

            // Academic/University signs
            upperText.contains("LAB") || upperText.contains("LABORATOR") ->
                handleDetection("LAB", "Laboratory detected: $text", "FORWARD")
            
            upperText.contains("HALL") || upperText.contains("AMFITEATRU") || upperText.contains("LECTURE") ->
                handleDetection("ACADEMIC", "Lecture hall or amfiteatru ahead.", "FORWARD")

            upperText.contains("LIBRARY") || upperText.contains("BIBLIOTECA") ->
                handleDetection("INFO", "Library nearby.", "FORWARD")
            
            upperText.contains("SECRETARY") || upperText.contains("SECRETARIAT") ->
                handleDetection("INFO", "Secretariat detected.", "FORWARD")

            upperText.contains("TOILET") || upperText.contains("WC") || upperText.contains("RESTROOM") ->
                handleDetection("INFO", "Restroom nearby.", "FORWARD")

            // Regex for Room/Office detection (e.g. Room 301, Office A, S-02)
            upperText.contains("ROOM") || upperText.contains("SALA") || upperText.contains("OFFICE") || 
            upperText.matches(Regex(".*\\b[A-Z]?[-]?\\d{3}\\b.*")) -> {
                val roomMsg = "Detected: $text"
                handleDetection("ROOM", roomMsg, "FORWARD")
                saveHistory("TEXT", roomMsg)
                matched = true
            }
        }
        return matched
    }

    private fun handleExitReached() {
        if (!isExitingHouse) return
        isExitingHouse = false
        
        currentVoiceState = VoiceState.ASKING_SWITCH
        speak("Exit detected. Would you like to switch to Outdoor Mode?", TextToSpeech.QUEUE_FLUSH, null, "READY_VOICE")
        
        binding.detectionBadge.text = "EXIT FOUND"
    }

    private fun handleDestinationSelected(destinationName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@NavigationActivity, Locale.US)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(destinationName, 1)
                
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val destLatLng = LatLng(address.latitude, address.longitude)
                    
                    withContext(Dispatchers.Main) {
                        if (ActivityCompat.checkSelfPermission(this@NavigationActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                location?.let {
                                    val currentLatLng = LatLng(it.latitude, it.longitude)
                                    fetchDirections(currentLatLng, destLatLng)
                                }
                            }
                        } else {
                            speak("Location permission is required for directions.")
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun fetchDirections(origin: LatLng, dest: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val apiKey = info.metaData.getString("com.google.android.geo.API_KEY")
                val urlString = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${dest.latitude},${dest.longitude}&mode=walking&key=$apiKey"
                val connection = URL(urlString).openConnection() as HttpURLConnection
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                
                if (json.getString("status") == "OK") {
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    val legs = route.getJSONArray("legs").getJSONObject(0)
                    val steps = legs.getJSONArray("steps")
                    val duration = legs.getJSONObject("duration").getString("text")
                    
                    withContext(Dispatchers.Main) {
                        navigationSteps.clear()
                        currentStepIndex = 0
                        for (i in 0 until steps.length()) {
                            val step = steps.getJSONObject(i)
                            val instruction = step.getString("html_instructions").replace(Regex("<.*?>"), "")
                            navigationSteps.add(instruction)
                        }
                        
                        val msg = "Route found. Travel time is $duration. Click the orange button to start."
                        showConfirmationUI(msg)
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun showConfirmationUI(message: String) {
        speak(message)
        binding.confirmationButton.visibility = View.VISIBLE
        binding.bottomSheet.visibility = View.GONE
        
        // Switch mode UI
        binding.modeSwitch.isChecked = true
        binding.modeLabel.text = "OUTDOOR MODE"
        
        startTimeout()
    }

    private fun startTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable { 
            binding.confirmationButton.visibility = View.GONE
            binding.bottomSheet.visibility = View.VISIBLE
            speak("Request timed out. Continuing in Indoor mode.")
        }
        mainHandler.postDelayed(timeoutRunnable!!, 60000)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
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

    private fun handleObstacleDetected(label: String, centerX: Int, imageWidth: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < THROTTLE_MS) return
        
        // Dynamic direction logic
        val third = imageWidth / 3
        val instruction = when {
            centerX < third + 100 -> "$label on left. Move right."
            centerX > 2 * third - 100 -> "$label on right. Move left."
            else -> "$label ahead. Stop or move around."
        }
        
        val command = when {
            centerX < third + 100 -> "RIGHT"
            centerX > 2 * third - 100 -> "LEFT"
            else -> "STOP"
        }
        
        handleDetection("OBSTACLE", instruction, command)
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

    private fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH, params: Bundle? = null, utteranceId: String? = null) {
        tts?.speak(text, queueMode, params, utteranceId)
    }

    private fun vibrateCommand(type: String) {
        if (!isAssistanceEnabled) return
        
        val path = when (type) {
            "LEFT" -> WearService.PATH_LEFT
            "RIGHT" -> WearService.PATH_RIGHT
            "STOP" -> WearService.PATH_DANGER
            else -> WearService.PATH_FORWARD
        }
        wearService.sendHapticSignal(path)
    }

    private fun startUdpListener() {
        if (udpStarted) return
        udpStarted = true
        
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
                        if (message == "DANGER") handleDetection("BEACON", "Danger ahead. Stop.", "STOP")
                    }
                }
            } catch (e: Exception) {
                udpStarted = false
                udpSocket?.close()
            }
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
