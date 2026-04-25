package com.example.accesnav

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Service to handle communication with Wear OS smartwatch.
 */
class WearService(context: Context) {
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    fun sendHapticSignal(path: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    messageClient.sendMessage(node.id, "/haptic/$path", byteArrayOf()).await()
                }
            } catch (e: Exception) {
                // Silently fail or log
            }
        }
    }

    companion object {
        const val PATH_SHORT = "short"
        const val PATH_LONG = "long"
        const val PATH_DANGER = "danger"
        const val PATH_TEXT = "text"
        const val PATH_LEFT = "left"
        const val PATH_RIGHT = "right"
        const val PATH_FORWARD = "forward"
    }
}
