package com.buddy.reminder.logic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiApiClient {

    private var cachedKey: String = ""

    private fun getKey(context: Context): String {
        if (cachedKey.isNotBlank()) return cachedKey
        return try {
            context.assets.open("gemini_key.txt").bufferedReader().use { it.readText().trim() }.also {
                cachedKey = it
            }
        } catch (e: Exception) {
            ""
        }
    }

    private const val SYSTEM_PROMPT = """
You are Buddy, an autonomous temporal reasoning engine.
Extract the scheduled event from user voice commands (even with typos or misheard speech like 'Concept' instead of 'Concert').

Rules:
1. title: Clear and concise.
2. category: FLIGHT, CONCERT, TRAIN, MOVIE, MEETING, DOCTOR, or GENERAL.
3. event_time: 24-hour HH:mm.
4. offset_minutes:
   - Flights: 180
   - Concerts: 120
   - Trains: 60
   - Movies: 45
   - Meetings: 10
   - Other: 15

Respond ONLY with valid raw JSON:
{"title":"String","category":"String","event_time":"HH:mm","offset_minutes":Integer}
"""

    suspend fun analyzeEvent(context: Context, text: String): String? = withContext(Dispatchers.IO) {
        val apiKey = getKey(context)
        if (apiKey.isBlank()) return@withContext null

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 7000
                readTimeout = 7000
            }

            val body = JSONObject().apply {
                val contents = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "$SYSTEM_PROMPT\n\nUser Input: \"$text\""))
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
