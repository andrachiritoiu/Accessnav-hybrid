package com.example.accesnav

import android.graphics.Rect
import com.google.mlkit.vision.objects.DetectedObject

/**
 * Robust Detection Pipeline for Assistive Navigation.
 * Follows a multi-layered approach: Analyze -> Filter -> Decide.
 */
class DetectionPipeline(
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    private val persistenceMap = mutableMapOf<Int, Int>()
    private val persistenceThreshold = 3
    private val confidenceThreshold = 0.6f
    
    // Danger Zone: 30%-70% width, 60%-100% height (bottom center)
    private val dangerZone = Rect(
        (screenWidth * 0.3).toInt(),
        (screenHeight * 0.6).toInt(),
        (screenWidth * 0.7).toInt(),
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
        
        // Cleanup persistence for lost objects (simplified for demo)
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
            if (!Rect.intersects(dangerZone, box)) {
                // If it's not in the danger zone, we might still care if it's very large
                // but for now, we follow the "Danger Zone" requirement strictly.
                continue
            }

            // 4. Proximity Estimation
            val areaPercent = (box.width() * box.height()).toFloat() / (screenWidth * screenHeight)
            val isClose = areaPercent > 0.15f

            // 5. Decision Mapping
            val centerX = box.centerX()
            val decision = when {
                isClose -> Decision.OBSTACLE_CLOSE
                topLabel.text.lowercase().contains("person") -> Decision.PERSON_AHEAD
                centerX < screenWidth / 3 -> Decision.OBSTACLE_LEFT
                centerX > 2 * screenWidth / 3 -> Decision.OBSTACLE_RIGHT
                else -> Decision.OBSTACLE_AHEAD
            }

            results.add(DetectionResult(decision, topLabel.text, topLabel.confidence))
        }
        
        return results
    }
}
