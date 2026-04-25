package com.example.accesnav

import android.graphics.Rect
import com.google.mlkit.vision.objects.DetectedObject

/**
 * Robust Detection Pipeline for Assistive Navigation.
 * Optimized for sensitivity and directional guidance.
 */
class DetectionPipeline(
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    private val persistenceMap = mutableMapOf<Int, Int>()
    private val persistenceThreshold = 2 // Lowered for faster detection
    private val confidenceThreshold = 0.45f // Increased sensitivity
    
    // Expanded Danger Zone for better coverage (10%-90% width)
    private val dangerZone = Rect(
        (screenWidth * 0.1).toInt(),
        (screenHeight * 0.4).toInt(),
        (screenWidth * 0.9).toInt(),
        screenHeight
    )

    enum class Decision {
        NONE, OBSTACLE_AHEAD, OBSTACLE_LEFT, OBSTACLE_RIGHT, OBSTACLE_CLOSE, PERSON_AHEAD
    }

    data class DetectionResult(
        val decision: Decision,
        val label: String,
        val confidence: Float
    )

    fun processObjects(objects: List<DetectedObject>): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        
        if (objects.isEmpty()) {
            persistenceMap.clear()
            return emptyList()
        }

        for (obj in objects) {
            val trackingId = obj.trackingId ?: -1
            
            // 1. Confidence Filter
            val topLabel = obj.labels.maxByOrNull { it.confidence }
            if (topLabel == null || (topLabel.confidence < confidenceThreshold)) continue
            
            // 2. Persistence Check
            val currentCount = persistenceMap.getOrDefault(trackingId, 0)
            persistenceMap[trackingId] = currentCount + 1
            if ((currentCount + 1) < persistenceThreshold) continue

            // 3. Spatial Filtering (Danger Zone)
            val box = obj.boundingBox
            if (!Rect.intersects(dangerZone, box)) continue

            // 4. Proximity Estimation
            val areaPercent = (box.width() * box.height()).toFloat() / (screenWidth * screenHeight)
            val isClose = areaPercent > 0.12f

            // 5. Decision Mapping
            val centerX = box.centerX()
            val decision = when {
                // Priority: People
                topLabel.text.lowercase().contains("person") || 
                topLabel.text.lowercase().contains("man") || 
                topLabel.text.lowercase().contains("woman") -> Decision.PERSON_AHEAD
                
                isClose -> Decision.OBSTACLE_CLOSE
                centerX < screenWidth / 3 -> Decision.OBSTACLE_LEFT
                centerX > 2 * screenWidth / 3 -> Decision.OBSTACLE_RIGHT
                else -> Decision.OBSTACLE_AHEAD
            }

            results.add(DetectionResult(decision, topLabel.text, topLabel.confidence))
        }
        
        // Prioritize Person_Ahead in the result list
        return results.sortedByDescending { it.decision == Decision.PERSON_AHEAD }
    }
}
