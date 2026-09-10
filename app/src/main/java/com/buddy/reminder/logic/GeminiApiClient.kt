package com.buddy.reminder.logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiApiClient {

    // Insert your Gemini API Key here
    private const val API_KEY = "YOUR_GEMINI_API_KEY"
    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$API_KEY"

    private const val SYSTEM_PROMPT = """
You are Buddy, an autonomous temporal reasoning and reminder scheduling engine.
Analyze user voice commands or notification text and extract event details.

You must:
1. Identify the event title (concise, clear).
2. Classify category (e.g., FLIGHT, CONCERT, TRAIN, MOVIE, MEETING, DOCTOR, GENERAL).
3. Extract the exact event time in 24-hour format (HH:mm).
4. Intelligently deduce the required advance preparation offset in minutes:
   - Flights: 180 (3 hours prior)
   - Concerts / Shows: 120 (2 hours prior)
   - Train / Bus departures: 60 (1 hour prior)
   - Movies / Cinema: 45 (45 minutes prior)
   - Doctor / Clinic visits: 30 (30 minutes prior)
   - Work / Casual Meetings: 10 (10 minutes prior)
   - Everything else: 15 (15 minutes prior)

Respond ONLY with a valid JSON object matching this schema, with no markdown fences, backticks, or extra text:
{"title":"String","category":"String","event_time":"HH:mm","offset_minutes":Integer}
"""

    suspend fun analyzeEvent(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
            }

            // Build request payload
            val body = JSONObject().apply {
                val contents = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "$SYSTEM_PROMPT\n\nInput: \"$text\""))
                        })
                    })
                }
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                    put("temperature", 0.1)
                })
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val jsonResponse = JSONObject(responseText)
                val candidates = jsonResponse.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    return@withContext parts.getJSONObject(0).getString("text")
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
