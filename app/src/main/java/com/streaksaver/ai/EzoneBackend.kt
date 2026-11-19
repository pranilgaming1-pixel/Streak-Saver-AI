package com.streaksaver.ai

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class ApiResponse(val ok: Boolean, val status: Int, val body: String)
data class EzoneProfile(val id: String, val username: String, val displayName: String, val bio: String = "", val avatarUrl: String? = null)
data class EzonePost(val id: String, val userId: String, val body: String, val mediaPath: String?, val mediaType: String?, val createdAt: String, val author: EzoneProfile?, val likedBy: Set<String>)
data class EzoneComment(val id: String, val userId: String, val body: String, val createdAt: String)
data class EzoneNotification(val id: String, val type: String, val message: String, val createdAt: String)

object EzoneSession {
    var accessToken by mutableStateOf("")
        private set
    var refreshToken by mutableStateOf("")
        private set
    var userId by mutableStateOf("")
        private set
    var email by mutableStateOf("")
        private set
    var expiresAt by mutableStateOf(0L)
        private set
    var recoveryMode by mutableStateOf(false)
        private set

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences("ezone", Context.MODE_PRIVATE)
        accessToken = prefs.getString("access_token", prefs.getString("token", "")).orEmpty()
        refreshToken = prefs.getString("refresh_token", "").orEmpty()
        userId = prefs.getString("user_id", "").orEmpty()
        email = prefs.getString("email", "").orEmpty()
        expiresAt = prefs.getLong("expires_at", 0L)
        recoveryMode = prefs.getBoolean("recovery_mode", false)
        if (userId.isBlank()) userId = jwtClaims(accessToken).optString("sub")
    }

    fun save(context: Context, access: String, refresh: String, user: String, mail: String, expiresInSeconds: Long) {
        val claims = jwtClaims(access)
        val resolvedUser = user.ifBlank { claims.optString("sub") }
        val resolvedEmail = mail.ifBlank { claims.optString("email") }
        val expiry = System.currentTimeMillis() + (expiresInSeconds.coerceAtLeast(60L) * 1000L)
        accessToken = access
        refreshToken = refresh
        userId = resolvedUser
        email = resolvedEmail
        expiresAt = expiry
        context.getSharedPreferences("ezone", Context.MODE_PRIVATE).edit()
            .putString("access_token", access)
            .putString("token", access)
            .putString("refresh_token", refresh)
            .putString("user_id", resolvedUser)
            .putString("email", resolvedEmail)
            .putLong("expires_at", expiry)
            .apply()
    }

    fun setRecoveryMode(context: Context, enabled: Boolean) {
        recoveryMode = enabled
        context.getSharedPreferences("ezone", Context.MODE_PRIVATE).edit()
            .putBoolean("recovery_mode", enabled)
            .apply()
    }

    fun clear(context: Context) {
        accessToken = ""
        refreshToken = ""
        userId = ""
        email = ""
        expiresAt = 0L
        recoveryMode = false
        context.getSharedPreferences("ezone", Context.MODE_PRIVATE).edit()
            .remove("access_token").remove("token").remove("refresh_token")
            .remove("user_id").remove("expires_at").remove("email")
            .remove("recovery_mode").apply()
    }

    fun isExpiringSoon(): Boolean = expiresAt > 0L && expiresAt < System.currentTimeMillis() + 120_000L

    fun jwtClaims(token: String): JSONObject {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) JSONObject()
            else JSONObject(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).toString(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            JSONObject()
        }
    }
}

object EzoneBackend {
    const val DEFAULT_URL = "https://mfitbsoomnzcvhjdiujl.supabase.co"
    const val DEFAULT_KEY = "sb_publishable_VDMBGM98M1bfhYOIvLoXrQ_BPD3-UvU"
    const val MEDIA_BUCKET = "ezone-media"
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun projectUrl(context: Context): String =
        context.getSharedPreferences("ezone", Context.MODE_PRIVATE).getString("url", DEFAULT_URL).orEmpty().trim().trimEnd('/')

    fun publishableKey(context: Context): String =
        context.getSharedPreferences("ezone", Context.MODE_PRIVATE).getString("key", DEFAULT_KEY).orEmpty().trim()

    fun request(
        context: Context,
        method: String,
        path: String,
        token: String = "",
        body: JSONObject? = null,
        prefer: String? = null,
        callback: (ApiResponse) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val response = performRequest(appContext, method, path, token, body, prefer)
            main.post { callback(response) }
        }
    }

    private fun performRequest(
        context: Context,
        method: String,
        path: String,
        token: String,
        body: JSONObject?,
        prefer: String?
    ): ApiResponse {
        var connection: HttpURLConnection? = null
        return try {
            val base = projectUrl(context)
            val key = publishableKey(context)
            if (!base.startsWith("https://") || key.isBlank()) {
                return ApiResponse(false, 0, "Supabase URL or publishable key is missing. Open Connection settings.")
            }
            connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("apikey", key)
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer " + token)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                if (!prefer.isNullOrBlank()) setRequestProperty("Prefer", prefer)
            }
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            ApiResponse(status in 200..299, status, responseBody)
        } catch (e: Exception) {
            ApiResponse(false, 0, e.localizedMessage ?: "Network error. Check your connection.")
        } finally {
            connection?.disconnect()
        }
    }

    fun upload(context: Context, uri: Uri, remotePath: String, token: String, callback: (ApiResponse) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var connection: HttpURLConnection? = null
            val response = try {
                val mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                val base = projectUrl(appContext)
                val key = publishableKey(appContext)
                val encodedPath = Uri.encode(remotePath, "/")
                connection = (URL(base + "/storage/v1/object/" + MEDIA_BUCKET + "/" + encodedPath)
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 90_000
                    doOutput = true
                    setChunkedStreamingMode(0)
                    setRequestProperty("apikey", key)
                    setRequestProperty("Authorization", "Bearer " + token)
                    setRequestProperty("Content-Type", mime)
                    setRequestProperty("x-upsert", "false")
                }
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not read the selected media.")
                input.use { source -> connection.outputStream.use { output -> source.copyTo(output) } }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                ApiResponse(status in 200..299, status, text)
            } catch (e: Exception) {
                ApiResponse(false, 0, e.localizedMessage ?: "Media upload failed.")
            } finally {
                connection?.disconnect()
            }
            main.post { callback(response) }
        }
    }

    fun refreshSession(context: Context, callback: (Boolean, String) -> Unit) {
        if (EzoneSession.refreshToken.isBlank()) {
            callback(false, "Your session cannot be renewed. Please log in again.")
            return
        }
        request(
            context, "POST", "/auth/v1/token?grant_type=refresh_token",
            body = JSONObject().put("refresh_token", EzoneSession.refreshToken)
        ) { result ->
            if (!result.ok) {
                callback(false, errorMessage(result, "Session refresh failed. Please log in again."))
                return@request
            }
            try {
                val json = JSONObject(result.body)
                EzoneSession.save(
                    context,
                    json.optString("access_token"),
                    json.optString("refresh_token", EzoneSession.refreshToken),
                    json.optJSONObject("user")?.optString("id").orEmpty(),
                    json.optJSONObject("user")?.optString("email").orEmpty(),
                    json.optLong("expires_in", 3600L)
                )
                callback(true, "Session restored.")
            } catch (_: Exception) {
                callback(false, "Could not restore your session. Please log in again.")
            }
        }
    }

    fun errorMessage(response: ApiResponse, fallback: String): String = try {
        val json = JSONObject(response.body)
        json.optString("msg")
            .ifBlank { json.optString("message") }
            .ifBlank { json.optString("error_description") }
            .ifBlank { json.optString("error") }
            .ifBlank { fallback }
    } catch (_: Exception) {
        response.body.take(220).ifBlank { fallback }
    }

    fun parseProfiles(body: String): List<EzoneProfile> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            EzoneProfile(
                item.optString("id"),
                item.optString("username"),
                item.optString("display_name").ifBlank { item.optString("username") },
                item.optString("bio"),
                item.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    fun parsePosts(body: String): List<EzonePost> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val authorJson = item.optJSONObject("profiles")
            val author = authorJson?.let {
                EzoneProfile(
                    it.optString("id"),
                    it.optString("username"),
                    it.optString("display_name").ifBlank { it.optString("username") },
                    it.optString("bio"),
                    it.optString("avatar_url").takeIf { value -> value.isNotBlank() && value != "null" }
                )
            }
            val likes = item.optJSONArray("post_likes") ?: JSONArray()
            val likedBy = (0 until likes.length()).mapNotNull { likes.optJSONObject(it)?.optString("user_id") }.toSet()
            EzonePost(
                item.optString("id"),
                item.optString("user_id"),
                item.optString("body"),
                item.optString("media_path").takeIf { it.isNotBlank() && it != "null" },
                item.optString("media_type").takeIf { it.isNotBlank() && it != "null" },
                item.optString("created_at"),
                author,
                likedBy
            )
        }
    }

    fun parseComments(body: String): List<EzoneComment> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            EzoneComment(item.optString("id"), item.optString("user_id"), item.optString("body"), item.optString("created_at"))
        }
    }

    fun mediaUrl(context: Context, remotePath: String): String =
        projectUrl(context) + "/storage/v1/object/public/" + MEDIA_BUCKET + "/" + Uri.encode(remotePath, "/")
}
