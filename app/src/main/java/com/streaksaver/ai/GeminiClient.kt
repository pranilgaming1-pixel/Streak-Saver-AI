package com.streaksaver.ai

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {
    private const val MODEL = "gemini-3.8-flash"

    fun generateMessage(apiKey: String, friendName: String, username: String, tone: String): String {
        require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings first." }
        val prompt = "Write one short, natural TikTok direct-message opener for a friend. " +
            "Friend name: " + friendName + ". Username: " + username + ". " +
            "Tone: " + tone + ". Keep it friendly, personal, non-spammy, and under 25 words. " +
            "Do not mention streaks unless it sounds natural. Return only the message."
        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))
            ))
            put("generationConfig", JSONObject().put("temperature", 0.8).put("maxOutputTokens", 100))
        }

        val connection = (URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                MODEL + ":generateContent"
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = try {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
                throw IOException(detail?.takeIf { it.isNotBlank() } ?: "Gemini request failed (HTTP " + status + ").")
            }
            val json = JSONObject(responseText)
            val parts = json.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text").orEmpty().trim()
            if (text.isBlank()) throw IOException("Gemini returned an empty message. Try again.")
            return text.removeSurrounding("\"").trim()
        } finally {
            connection.disconnect()
        }
    }
}
