package com.streaksaver.ai

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ApiResponse(val ok: Boolean, val status: Int, val body: String)
data class EzoneProfile(val id: String, val username: String, val displayName: String, val bio: String = "", val avatarUrl: String? = null, val website: String = "", val location: String = "", val pronouns: String = "", val accentColor: String = "#ED233C")
data class EzonePost(val id: String, val userId: String, val body: String, val mediaPath: String?, val mediaType: String?, val createdAt: String, val author: EzoneProfile?, val likedBy: Set<String>, val musicPath: String? = null, val musicTitle: String? = null, val musicArtist: String? = null)
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

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
        return try {
            val base = projectUrl(context)
            val key = publishableKey(context)
            if (!base.startsWith("https://") || key.isBlank()) {
                return ApiResponse(false, 0, "Supabase URL or publishable key is missing. Open Connection settings.")
            }
            val requestBody = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            val builder = Request.Builder()
                .url(base + path)
                .header("apikey", key)
                .header("Accept", "application/json")
            if (token.isNotBlank()) builder.header("Authorization", "Bearer " + token)
            if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer)
            val request = builder.method(method.uppercase(), requestBody).build()
            client.newCall(request).execute().use { response ->
                ApiResponse(response.isSuccessful, response.code, response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            ApiResponse(false, 0, e.localizedMessage ?: "Network error. Check your connection.")
        }
    }

    fun upload(context: Context, uri: Uri, remotePath: String, token: String, callback: (ApiResponse) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val response = try {
                val base = projectUrl(appContext)
                val key = publishableKey(appContext)
                if (!base.startsWith("https://") || key.isBlank()) {
                    ApiResponse(false, 0, "Supabase URL or publishable key is missing.")
                } else {
                    val size = try {
                        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    } catch (_: Exception) { -1L }
                    if (size > 104857600L) {
                        ApiResponse(false, 413, "Choose a photo, video or audio file smaller than 100 MB.")
                    } else {
                    val mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                    val encodedPath = Uri.encode(remotePath, "/")
                    val streamingBody = object : RequestBody() {
                        override fun contentType() = mime.toMediaTypeOrNull()
                        override fun contentLength(): Long = size
                        override fun writeTo(sink: BufferedSink) {
                            val input = appContext.contentResolver.openInputStream(uri)
                                ?: throw IllegalStateException("Could not read the selected media.")
                            input.use { stream -> stream.source().use { source -> sink.writeAll(source) } }
                        }
                    }
                    val request = Request.Builder()
                        .url(base + "/storage/v1/object/" + MEDIA_BUCKET + "/" + encodedPath)
                        .header("apikey", key)
                        .header("Authorization", "Bearer " + token)
                        .header("x-upsert", "false")
                        .post(streamingBody)
                        .build()
                    client.newCall(request).execute().use { result ->
                        ApiResponse(result.isSuccessful, result.code, result.body?.string().orEmpty())
                    }
                    }
                }
            } catch (e: Exception) {
                ApiResponse(false, 0, e.localizedMessage ?: "Media upload failed.")
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
                item.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
                item.optString("website").takeIf { it != "null" }.orEmpty(),
                item.optString("location").takeIf { it != "null" }.orEmpty(),
                item.optString("pronouns").takeIf { it != "null" }.orEmpty(),
                item.optString("accent_color").takeIf { it != "null" }.orEmpty().ifBlank { "#ED233C" }
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
                    it.optString("avatar_url").takeIf { value -> value.isNotBlank() && value != "null" },
                    it.optString("website").takeIf { value -> value != "null" }.orEmpty(),
                    it.optString("location").takeIf { value -> value != "null" }.orEmpty(),
                    it.optString("pronouns").takeIf { value -> value != "null" }.orEmpty(),
                    it.optString("accent_color").takeIf { value -> value != "null" }.orEmpty().ifBlank { "#ED233C" }
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
                likedBy,
                item.optString("music_path").takeIf { it.isNotBlank() && it != "null" },
                item.optString("music_title").takeIf { it.isNotBlank() && it != "null" },
                item.optString("music_artist").takeIf { it.isNotBlank() && it != "null" }
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
