package com.example.accesnav

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiService(apiKey: String) {
    private val model = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey
    )

    suspend fun describeScene(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        android.util.Log.d("GeminiService", "Attempting to describe scene... Bitmap size: ${bitmap.width}x${bitmap.height}")
        try {
            val response = model.generateContent(
                content {
                    image(bitmap)
                    text("You are a high-precision assistive guide for a blind student. \n" +
                         "Analyze this image and provide immediate spatial guidance:\n" +
                         "1. List key obstacles and their RELATIVE POSITION (e.g., 'Chairs on your left', 'Table ahead').\n" +
                         "2. Read any visible signs or room numbers (e.g., 'Amfiteatru 1').\n" +
                         "3. Give a clear walking instruction (e.g., 'The path is clear towards the right side of the hallway').\n" +
                         "Be concise, direct, and focus on safe movement. Max 4 sentences.")
                }
            )
            val result = response.text
            if (result != null) {
                android.util.Log.d("GeminiService", "Description received: $result")
                result
            } else {
                android.util.Log.w("GeminiService", "No text in Gemini response.")
                "I see the surroundings but cannot describe them right now."
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiService", "Gemini error: ${e.message}", e)
            "AI Vision is currently unavailable. Please check your internet or API key."
        }
    }
}
