package com.example.accesnav.wear

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

class MainActivity : AppCompatActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        if (path.startsWith("/haptic/")) {
            val type = path.removePrefix("/haptic/")
            triggerHaptic(type)
        }
    }

    private fun triggerHaptic(type: String) {
        val pattern = when (type) {
            "right" -> longArrayOf(0, 300) // 1 pulse
            "left" -> longArrayOf(0, 300, 150, 300) // 2 pulses
            "forward" -> longArrayOf(0, 800) // Long pulse
            "danger" -> longArrayOf(0, 200, 100, 200, 100, 200) // Repeated pulses
            "text" -> longArrayOf(0, 100, 50, 100) // Quick double tap
            else -> longArrayOf(0, 150) // Short pulse
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onDestroy()
    }
}
