package com.streaksaver.ai

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay

private val EzoneBlack = Color(0xFF050505)
private val EzoneSurface = Color(0xFF111111)
private val EzoneSurface2 = Color(0xFF1B1B1B)
private val EzoneWhite = Color(0xFFFFFFFF)
private val EzoneMuted = Color(0xFFAAAAAA)
private val EzoneRed = Color(0xFFFFFFFF)
private val EzoneLine = Color(0xFF292929)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(5, 5, 5)
        window.navigationBarColor = AndroidColor.rgb(5, 5, 5)
        EzoneSession.restore(this)
        handleAuthCallback(intent)
        setContent { EzoneApp(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "ezone" || uri.host != "auth") return
        val values = LinkedHashMap<String, String>()
        uri.fragment.orEmpty().split("&").forEach { part ->
            val pair = part.split("=", limit = 2)
            if (pair.size == 2) values[pair[0]] = Uri.decode(pair[1])
        }
        val access = values["access_token"].orEmpty()
        val refresh = values["refresh_token"].orEmpty()
        if (access.isNotBlank()) {
            val claims = EzoneSession.jwtClaims(access)
            val recovery = values["type"] == "recovery" || uri.getQueryParameter("flow") == "recovery"
            EzoneSession.save(
                this,
                access,
                refresh,
                claims.optString("sub"),
                claims.optString("email"),
                values["expires_in"]?.toLongOrNull() ?: 3600L
            )
            EzoneSession.setRecoveryMode(this, recovery)
            Toast.makeText(this, if (recovery) "Choose a new password." else "Signed in to Ezone.", Toast.LENGTH_SHORT).show()
        } else {
            val error = values["error_description"] ?: uri.getQueryParameter("error_description")
            if (!error.isNullOrBlank()) Toast.makeText(this, Uri.decode(error), Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun EzoneApp(context: Context) {
    var sessionNotice by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = EzoneRed,
        onPrimary = Color(0xFF050505),
        background = EzoneBlack,
        surface = EzoneSurface,
        onSurface = EzoneWhite
    )) {
        Surface(Modifier.fillMaxSize(), color = EzoneBlack) {
            if (EzoneSession.accessToken.isBlank()) {
                AuthScreen(context)
            } else {
                LaunchedEffect(EzoneSession.accessToken, EzoneSession.expiresAt) {
                    val tokenAtStart = EzoneSession.accessToken
                    if (tokenAtStart.isNotBlank() && EzoneSession.expiresAt > 0L) {
                        // Wait until two minutes before expiry instead of checking only once
                        // when the screen is first composed.
                        val waitMillis = (EzoneSession.expiresAt - System.currentTimeMillis() - 120_000L)
                            .coerceAtLeast(0L)
                        if (waitMillis > 0L) delay(waitMillis)
                        if (EzoneSession.accessToken == tokenAtStart && EzoneSession.isExpiringSoon()) {
                            EzoneBackend.refreshSession(context) { ok, message ->
                                sessionNotice = if (ok) "" else message
                            }
                        }
                    }
                }
                EzoneMainScreen(
                    context = context,
                    sessionNotice = sessionNotice,
                    onDismissNotice = { sessionNotice = "" }
                )
            }
        }
    }
}

@Composable
private fun AuthScreen(context: Context) {
    val prefs = remember { context.getSharedPreferences("ezone", Context.MODE_PRIVATE) }
    var email by remember { mutableStateOf(prefs.getString("email", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("login") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var projectUrl by remember { mutableStateOf(EzoneBackend.projectUrl(context)) }
    var publishableKey by remember { mutableStateOf(EzoneBackend.publishableKey(context)) }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF171717), Color(0xFF090909), EzoneBlack))
        )
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(34.dp))
            Text("ezone", color = EzoneWhite, fontSize = 47.sp, fontWeight = FontWeight.Black, letterSpacing = (-2.5).sp)
            Spacer(Modifier.height(6.dp))
            Text("Share your world", color = EzoneMuted, fontSize = 14.sp)
            Spacer(Modifier.height(34.dp))

            if (settingsOpen) {
                Text("Connection settings", color = EzoneWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Use your Supabase project URL and publishable key.", color = EzoneMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                AuthField(projectUrl, { projectUrl = it }, "Supabase Project URL", KeyboardType.Uri)
                AuthField(publishableKey, { publishableKey = it }, "Publishable key")
                PrimaryAction("Save settings", busy) {
                    if (!projectUrl.trim().startsWith("https://") || publishableKey.trim().isBlank()) {
                        message = "Enter a valid HTTPS project URL and publishable key."
                    } else {
                        prefs.edit()
                            .putString("url", projectUrl.trim().trimEnd('/'))
                            .putString("key", publishableKey.trim())
                            .apply()
                        settingsOpen = false
                        message = "Connection settings saved."
                    }
                }
                TextButton(onClick = { settingsOpen = false }) { Text("Back to sign in", color = EzoneMuted) }
            } else {
                Text(
                    if (mode == "signup") "Create your account" else "Welcome back",
                    color = EzoneWhite, fontSize = 23.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (mode == "signup") "Join Ezone and find your people." else "Log in to continue to Ezone.",
                    color = EzoneMuted, fontSize = 13.sp
                )
                Spacer(Modifier.height(20.dp))
                if (mode == "signup") {
                    AuthField(fullName, { fullName = it }, "Full name")
                    AuthField(username, { username = it }, "Username")
                }
                AuthField(email, { email = it }, "Email address", KeyboardType.Email)
                AuthField(password, { password = it }, "Password", secret = true)
                Spacer(Modifier.height(8.dp))
                PrimaryAction(
                    if (busy) "Please wait…" else if (mode == "signup") "Create account" else "Log in",
                    busy
                ) {
                    val cleanEmail = email.trim().lowercase()
                    val cleanUsername = username.trim().removePrefix("@").lowercase()
                    when {
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches() ->
                            message = "Enter a valid email address."
                        password.length < 6 ->
                            message = "Your password must contain at least 6 characters."
                        mode == "signup" && fullName.trim().isBlank() ->
                            message = "Enter your name."
                        mode == "signup" && !cleanUsername.matches(Regex("[a-z0-9_]{3,24}")) ->
                            message = "Username must be 3–24 characters using letters, numbers or underscores."
                        else -> {
                            busy = true
                            message = ""
                            val body = JSONObject().put("email", cleanEmail).put("password", password)
                            val endpoint = if (mode == "login") {
                                "/auth/v1/token?grant_type=password"
                            } else {
                                body.put("data", JSONObject()
                                    .put("display_name", fullName.trim())
                                    .put("username", cleanUsername))
                                "/auth/v1/signup?redirect_to=" + Uri.encode("ezone://auth")
                            }
                            EzoneBackend.request(context, "POST", endpoint, body = body) { result ->
                                busy = false
                                if (!result.ok) {
                                    message = EzoneBackend.errorMessage(result, "Could not complete authentication.")
                                } else {
                                    try {
                                        val json = JSONObject(result.body)
                                        val access = json.optString("access_token")
                                        if (access.isNotBlank()) {
                                            val user = json.optJSONObject("user")
                                            EzoneSession.save(
                                                context,
                                                access,
                                                json.optString("refresh_token"),
                                                user?.optString("id").orEmpty(),
                                                user?.optString("email").orEmpty().ifBlank { cleanEmail },
                                                json.optLong("expires_in", 3600L)
                                            )
                                            message = ""
                                        } else {
                                            message = "Account created. Check your email to confirm it, then log in."
                                            mode = "login"
                                            password = ""
                                        }
                                    } catch (_: Exception) {
                                        message = "The server response was incomplete. Try logging in."
                                    }
                                }
                            }
                        }
                    }
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = if (message.contains("created", true) || message.contains("confirm", true)) Color(0xFF9DE4B8) else Color(0xFFBDBDBD), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                if (mode == "login") {
                    TextButton(enabled = !busy, onClick = {
                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                            message = "Enter your email address first."
                        } else {
                            busy = true
                            val redirect = Uri.encode("ezone://auth?flow=recovery")
                            EzoneBackend.request(
                                context, "POST",
                                "/auth/v1/recover?redirect_to=" + redirect,
                                body = JSONObject().put("email", email.trim())
                            ) { result ->
                                busy = false
                                message = if (result.ok) {
                                    "If that account exists, a password reset email has been sent."
                                } else {
                                    EzoneBackend.errorMessage(result, "Could not send the reset email.")
                                }
                            }
                        }
                    }) { Text("Forgot password?", color = EzoneMuted, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = EzoneLine)
                    Text("  OR  ", color = EzoneMuted, fontSize = 10.sp)
                    HorizontalDivider(Modifier.weight(1f), color = EzoneLine)
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        val redirect = Uri.encode("ezone://auth")
                        val authUrl = EzoneBackend.projectUrl(context) +
                            "/auth/v1/authorize?provider=google&redirect_to=" + redirect
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                        } catch (_: Exception) {
                            message = "Could not open Google sign-in. Check that a browser is installed."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EzoneWhite),
                    border = BorderStroke(1.dp, Color(0xFF303030))
                ) {
                    Text("G", color = EzoneRed, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Continue with Google", color = EzoneWhite, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = EzoneLine)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(if (mode == "login") "Don't have an account? " else "Already have an account? ", color = EzoneMuted, fontSize = 13.sp)
                    TextButton(enabled = !busy, onClick = {
                        mode = if (mode == "login") "signup" else "login"
                        message = ""
                    }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (mode == "login") "Sign up" else "Log in", color = EzoneWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                TextButton(onClick = { settingsOpen = true; message = "" }) {
                    Text("Connection settings", color = Color(0xFF77848C), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("BLACK  ·  WHITE  ·  RED", color = EzoneRed, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Text("By continuing, you agree to Ezone's Terms and Privacy Policy.", color = Color(0xFF606A70), fontSize = 10.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        label = { Text(label, color = EzoneMuted, fontSize = 12.sp) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = EzoneWhite, unfocusedTextColor = EzoneWhite,
            focusedBorderColor = EzoneWhite, unfocusedBorderColor = Color(0xFF454545),
            focusedContainerColor = EzoneSurface, unfocusedContainerColor = EzoneSurface,
            cursorColor = EzoneRed
        )
    )
}

@Composable
private fun PrimaryAction(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !disabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = EzoneRed,
            contentColor = Color(0xFF061018),
            disabledContainerColor = Color(0xFF303030)
        )
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EzoneMainScreen(
    context: Context,
    sessionNotice: String,
    onDismissNotice: () -> Unit
) {
    val appPrefs = remember { context.getSharedPreferences("ezone_app_settings", Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf("Home") }
    var feedMode by remember { mutableStateOf(appPrefs.getString("default_feed", "For you") ?: "For you") }
    var autoplayReels by remember { mutableStateOf(appPrefs.getBoolean("autoplay_reels", true)) }
    var autoplayFeedVideos by remember { mutableStateOf(appPrefs.getBoolean("autoplay_feed_videos", false)) }
    var dataSaver by remember { mutableStateOf(appPrefs.getBoolean("data_saver", false)) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var targetProfileId by remember { mutableStateOf(EzoneSession.userId) }
    var messagesOpen by remember { mutableStateOf(false) }
    var chatUserId by remember { mutableStateOf("") }
    var chatUserName by remember { mutableStateOf("Ezone member") }
    var profile by remember { mutableStateOf<EzoneProfile?>(null) }
    var profilePosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var taggedPosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var pinnedPostIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var profileSection by remember { mutableStateOf("Posts") }
    var showProfileInsights by remember { mutableStateOf(false) }
    var profileActionDialog by remember { mutableStateOf("") }
    var reportReason by remember { mutableStateOf("spam") }
    var feedPosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var savedPosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var explorePosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var exploreFilter by remember { mutableStateOf("For you") }
    var activityFilter by remember { mutableStateOf("All") }
    var reelPosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<EzoneProfile>>(emptyList()) }
    var followingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var notifications by remember { mutableStateOf<List<EzoneNotification>>(emptyList()) }
    var isFollowingTarget by remember { mutableStateOf(false) }
    var isFollowPending by remember { mutableStateOf(false) }
    var isPrivateAccount by remember { mutableStateOf(false) }
    var notifyLikes by remember { mutableStateOf(true) }
    var notifyComments by remember { mutableStateOf(true) }
    var notifyFollows by remember { mutableStateOf(true) }
    var notifyMessages by remember { mutableStateOf(true) }
    var followRequestsOpen by remember { mutableStateOf(false) }
    var blockedUsersOpen by remember { mutableStateOf(false) }
    var profileFollowerCount by remember { mutableIntStateOf(0) }
    var profileFollowingCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf("") }
    var activeCommentsPost by remember { mutableStateOf<EzonePost?>(null) }
    var selectedStory by remember { mutableStateOf<JSONObject?>(null) }
    var editProfileOpen by remember { mutableStateOf(false) }
    var recoveryDialogOpen by remember { mutableStateOf(EzoneSession.recoveryMode) }
    LaunchedEffect(EzoneSession.recoveryMode) { if (EzoneSession.recoveryMode) recoveryDialogOpen = true }
    val token = EzoneSession.accessToken
    val myId = EzoneSession.userId
    val visibleProfilePosts = when (profileSection) {
        "Reels" -> profilePosts.filter { it.mediaType == "video" }
        "Tagged" -> taggedPosts
        else -> profilePosts.sortedByDescending { it.id in pinnedPostIds }
    }

    LaunchedEffect(token, myId) {
        EzoneBackend.request(context, "GET", "/rest/v1/profiles?select=is_private&id=eq." + myId + "&limit=1", token) { ownProfile ->
            if (ownProfile.ok) isPrivateAccount = try { JSONArray(ownProfile.body).optJSONObject(0)?.optBoolean("is_private", false) ?: false } catch (_: Exception) { false }
        }
        EzoneBackend.request(context, "GET", "/rest/v1/notification_preferences?select=likes,comments,follows,messages&user_id=eq." + myId + "&limit=1", token) { prefs ->
            if (prefs.ok) try {
                val item = JSONArray(prefs.body).optJSONObject(0)
                if (item != null) { notifyLikes = item.optBoolean("likes", true); notifyComments = item.optBoolean("comments", true); notifyFollows = item.optBoolean("follows", true); notifyMessages = item.optBoolean("messages", true) }
            } catch (_: Exception) { }
        }
        EzoneBackend.request(context, "GET", "/rest/v1/follows?select=following_id&follower_id=eq." + myId, token) { follows ->
            if (follows.ok) {
                val arr = try { JSONArray(follows.body) } catch (_: Exception) { JSONArray() }
                followingIds = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("following_id") }.toSet()
            }
        }
    }

    LaunchedEffect(tab, feedMode, reloadKey, targetProfileId, token) {
        pageError = ""
        when (tab) {
            "Home" -> {
                loading = true
                if (feedMode == "Following") {
                    EzoneBackend.request(context, "GET", "/rest/v1/follows?select=following_id&follower_id=eq." + myId, token) { follows ->
                        if (!follows.ok) {
                            loading = false
                            pageError = EzoneBackend.errorMessage(follows, "Could not load the people you follow.")
                        } else {
                            val arr = try { JSONArray(follows.body) } catch (_: Exception) { JSONArray() }
                            val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("following_id") }.filter { it.isNotBlank() }
                            if (ids.isEmpty()) {
                                feedPosts = emptyList()
                                loading = false
                            } else {
                                val inFilter = ids.joinToString(",")
                                EzoneBackend.request(
                                    context, "GET",
                                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color,is_private),post_likes(user_id)&user_id=in.(" + inFilter + ")&order=created_at.desc&limit=50",
                                    token
                                ) { result ->
                                    loading = false
                                    if (result.ok) feedPosts = EzoneBackend.parsePosts(result.body)
                                    else pageError = EzoneBackend.errorMessage(result, "Could not load your feed.")
                                }
                            }
                        }
                    }
                } else {
                    EzoneBackend.request(
                        context, "GET",
                        "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url),post_likes(user_id)&order=created_at.desc&limit=50",
                        token
                    ) { result ->
                        loading = false
                        if (result.ok) feedPosts = EzoneBackend.parsePosts(result.body)
                        else pageError = EzoneBackend.errorMessage(result, "Could not load your feed.")
                    }
                }
                EzoneBackend.request(context, "GET", "/rest/v1/follows?select=following_id&follower_id=eq." + myId, token) { follows ->
                    if (follows.ok) {
                        val arr = try { JSONArray(follows.body) } catch (_: Exception) { JSONArray() }
                        followingIds = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("following_id") }.toSet()
                    }
                }
            }
            "Explore" -> {
                loading = true
                EzoneBackend.request(
                    context, "GET",
                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url),post_likes(user_id)&order=created_at.desc&limit=60",
                    token
                ) { result ->
                    loading = false
                    if (result.ok) explorePosts = EzoneBackend.parsePosts(result.body)
                    else pageError = EzoneBackend.errorMessage(result, "Could not load Explore.")
                }
            }
            "Reels" -> {
                loading = true
                EzoneBackend.request(
                    context, "GET",
                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url),post_likes(user_id)&media_type=eq.video&order=created_at.desc&limit=60",
                    token
                ) { result ->
                    loading = false
                    if (result.ok) reelPosts = EzoneBackend.parsePosts(result.body)
                    else pageError = EzoneBackend.errorMessage(result, "Could not load Reels.")
                }
            }
            "Profile" -> {
                loading = true
                profile = null
                profilePosts = emptyList()
                taggedPosts = emptyList()
                pinnedPostIds = emptySet()
                profileSection = "Posts"
                EzoneBackend.request(
                    context, "GET",
                    "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color,is_private&id=eq." + targetProfileId + "&limit=1",
                    token
                ) { result ->
                    if (result.ok) {
                        profile = EzoneBackend.parseProfiles(result.body).firstOrNull()
                        if (targetProfileId == myId) isPrivateAccount = profile?.isPrivate == true
                        if (profile == null && targetProfileId == myId) {
                            val handle = "member_" + myId.replace("-", "").take(12)
                            val display = EzoneSession.email.substringBefore("@").ifBlank { "Ezone member" }
                            val bootstrap = JSONObject().put("id", myId).put("username", handle).put("display_name", display)
                            EzoneBackend.request(context, "POST", "/rest/v1/profiles", token, bootstrap, "return=representation") { created ->
                                if (created.ok) {
                                    profile = EzoneBackend.parseProfiles(created.body).firstOrNull()
                                    if (profile == null) reloadKey++
                                } else {
                                    pageError = "Your profile could not be created: " + EzoneBackend.errorMessage(created, "Check that the Ezone Supabase schema is installed.")
                                }
                            }
                        }
                    } else {
                        profile = null
                        pageError = EzoneBackend.errorMessage(result, "Could not load this profile. Check your Supabase setup.")
                    }
                    EzoneBackend.request(
                        context, "GET",
                        "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url),post_likes(user_id)&user_id=eq." + targetProfileId + "&order=created_at.desc&limit=50",
                        token
                    ) { postResult ->
                        loading = false
                        if (postResult.ok) profilePosts = EzoneBackend.parsePosts(postResult.body)
                        else pageError = EzoneBackend.errorMessage(postResult, "Could not load this profile's posts.")
                    }
                }
                EzoneBackend.request(context, "GET", "/rest/v1/profile_post_pins?select=post_id&user_id=eq." + targetProfileId, token) { pins ->
                    if (pins.ok) {
                        val arr = try { JSONArray(pins.body) } catch (_: Exception) { JSONArray() }
                        pinnedPostIds = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("post_id") }.toSet()
                    }
                }
                EzoneBackend.request(context, "GET", "/rest/v1/tagged_posts?select=post_id&tagged_user_id=eq." + targetProfileId + "&order=created_at.desc&limit=50", token) { tags ->
                    if (tags.ok) {
                        val arr = try { JSONArray(tags.body) } catch (_: Exception) { JSONArray() }
                        val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("post_id") }.filter { it.isNotBlank() }
                        if (ids.isNotEmpty()) {
                            EzoneBackend.request(context, "GET", "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url),post_likes(user_id)&id=in.(" + ids.joinToString(",") + ")&order=created_at.desc", token) { tagged ->
                                if (tagged.ok) taggedPosts = EzoneBackend.parsePosts(tagged.body)
                                else pageError = EzoneBackend.errorMessage(tagged, "Could not load tagged posts.")
                            }
                        } else taggedPosts = emptyList()
                    } else pageError = EzoneBackend.errorMessage(tags, "Could not load tagged posts.")
                }
                if (targetProfileId != myId) {
                    EzoneBackend.request(context, "GET", "/rest/v1/follow_requests?select=id&follower_id=eq." + myId + "&owner_id=eq." + targetProfileId + "&status=eq.pending&limit=1", token) { pending ->
                        isFollowPending = pending.ok && try { JSONArray(pending.body).length() > 0 } catch (_: Exception) { false }
                    }
                    EzoneBackend.request(
                        context, "GET",
                        "/rest/v1/follows?select=id&follower_id=eq." + myId + "&following_id=eq." + targetProfileId + "&limit=1",
                        token
                    ) { result ->
                        isFollowingTarget = result.ok && try { JSONArray(result.body).length() > 0 } catch (_: Exception) { false }
                    }
                }
            }
            "Saved" -> {
                loading = true
                EzoneBackend.request(context, "GET", "/rest/v1/saved_posts?select=post_id&user_id=eq." + myId + "&order=created_at.desc&limit=100", token) { saved ->
                    if (!saved.ok) { loading = false; pageError = EzoneBackend.errorMessage(saved, "Could not load saved posts.") }
                    else {
                        val ids = try { val arr = JSONArray(saved.body); (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("post_id") }.filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
                        if (ids.isEmpty()) { savedPosts = emptyList(); loading = false }
                        else EzoneBackend.request(context, "GET", "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url),post_likes(user_id)&id=in.(" + ids.joinToString(",") + ")&order=created_at.desc", token) { posts ->
                            loading = false
                            if (posts.ok) savedPosts = EzoneBackend.parsePosts(posts.body) else pageError = EzoneBackend.errorMessage(posts, "Could not load saved posts.")
                        }
                    }
                }
            }
            "Activity" -> {
                loading = true
                EzoneBackend.request(
                    context, "GET",
                    "/rest/v1/notifications?select=id,type,message,created_at&user_id=eq." + myId + "&order=created_at.desc&limit=60",
                    token
                ) { result ->
                    loading = false
                    if (result.ok) {
                        val arr = try { JSONArray(result.body) } catch (_: Exception) { JSONArray() }
                        notifications = (0 until arr.length()).mapNotNull { i ->
                            val item = arr.optJSONObject(i) ?: return@mapNotNull null
                            EzoneNotification(item.optString("id"), item.optString("type"), item.optString("message"), item.optString("created_at"))
                        }
                    } else pageError = EzoneBackend.errorMessage(result, "Could not load activity.")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(EzoneBlack)) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(EzoneBlack).padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).clickable { if (tab == "Home") tab = "Home" else { tab = "Home"; targetProfileId = myId } }, contentAlignment = Alignment.CenterStart) {
                Text(if (tab == "Home") "ℇ" else "‹", color = EzoneWhite, fontSize = if (tab == "Home") 30.sp else 34.sp, fontWeight = FontWeight.Normal)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(when(tab) { "Home" -> "EZONE"; "Profile" -> profile?.displayName ?: "Profile"; "Create" -> "New thread"; "Activity" -> "Activity"; "Explore" -> "Explore"; "Search" -> "Search"; "Settings" -> "Settings"; else -> tab },
                    color = EzoneWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tab == "Home") {
                    Box(Modifier.size(38.dp).clickable { tab = "Activity" }, contentAlignment = Alignment.CenterEnd) {
                        Text("♡", color = EzoneWhite, fontSize = 28.sp)
                    }
                } else if (tab == "Profile") {
                    Box(Modifier.size(32.dp).clickable { tab = "Create" }, contentAlignment = Alignment.Center) { Text("▣", color = EzoneWhite, fontSize = 19.sp) }
                    Box(Modifier.size(32.dp).clickable { tab = "Settings" }, contentAlignment = Alignment.Center) { Text("☰", color = EzoneWhite, fontSize = 20.sp) }
                } else if (tab == "Explore") {
                    Box(Modifier.size(38.dp).clickable { tab = "Search" }, contentAlignment = Alignment.CenterEnd) { Text("⌕", color = EzoneWhite, fontSize = 25.sp) }
                } else if (tab == "Messages") {
                    Box(Modifier.size(38.dp).clickable { reloadKey++ }, contentAlignment = Alignment.CenterEnd) { Text("✎", color = EzoneWhite, fontSize = 20.sp) }
                }
            }
        }
        HorizontalDivider(color = EzoneLine)
        if (sessionNotice.isNotBlank()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF202020)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sessionNotice, color = Color(0xFFEAEAEA), fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissNotice) { Text("OK", color = EzoneRed) }
            }
        }
        when (tab) {
            "Home" -> {
                Row(Modifier.fillMaxWidth().background(EzoneBlack).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    listOf("For you", "Following").forEach { item ->
                        val active = feedMode == item
                        Column(Modifier.weight(1f).clickable { feedMode = item; appPrefs.edit().putString("default_feed", item).apply() }.padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(item, color = if (active) EzoneWhite else EzoneMuted, fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(if (active) EzoneWhite else Color.Transparent))
                        }
                    }
                }
                HorizontalDivider(color = EzoneLine)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        StoriesRail(context = context, refreshKey = reloadKey, onStoryPosted = { reloadKey++ }, onOpenStory = { selectedStory = it }, onOpenProfile = { id -> targetProfileId = id; tab = "Profile" })
                        HorizontalDivider(color = EzoneLine)
                    }
                    if (loading && feedPosts.isEmpty()) {
                        item { LoadingLine("Loading your feed…") }
                    } else if (feedPosts.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                EmptyState(
                                    if (feedMode == "Following") "Your circle is quiet" else "Your Ezone starts here",
                                    if (feedMode == "Following") "Follow people to see their posts here." else "Be the first to share something. Your feed contains real Ezone members, not demo profiles."
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { tab = if (feedMode == "Following") "Explore" else "Create" },
                                    colors = ButtonDefaults.buttonColors(containerColor = EzoneWhite, contentColor = EzoneBlack),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Text(
                                        if (feedMode == "Following") "Find people" else "Create your first post",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        items(feedPosts, key = { it.id }) { post ->
                            PostCard(
                                context, post, myId, autoplayFeedVideos, dataSaver,
                                onLike = { likePost(context, token, myId, post) { reloadKey++ } },
                                onComments = { activeCommentsPost = post },
                                onProfile = { id ->
                                    targetProfileId = id
                                    tab = "Profile"
                                }
                            )
                            HorizontalDivider(color = EzoneLine)
                        }
                    }
                    if (pageError.isNotBlank()) item { ErrorLine(pageError) }
                }
            }
            "Create" -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        PostComposer(context, onPosted = { tab = "Home"; reloadKey++ })
                    }
                }
            }
            "Explore" -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 7.dp)) {
                            Spacer(Modifier.height(2.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                listOf("For you", "Trending", "Recent").forEach { filter ->
                                    Column(Modifier.weight(1f).clickable { exploreFilter = filter }.padding(top = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(filter, color = if (exploreFilter == filter) EzoneWhite else EzoneMuted, fontSize = 12.sp, fontWeight = if (exploreFilter == filter) FontWeight.Bold else FontWeight.Normal)
                                        Spacer(Modifier.height(9.dp))
                                        Box(Modifier.fillMaxWidth().height(1.dp).background(if (exploreFilter == filter) EzoneWhite else Color.Transparent))
                                    }
                                }
                            }
                        }
                    }
                    if (loading && explorePosts.isEmpty()) item { LoadingLine("Finding something new…") }
                    if (explorePosts.isEmpty() && !loading) item { EmptyState("Your Explore is waiting", "When members share photos and videos, they will appear here.") }
                    items((if (exploreFilter == "Trending") explorePosts.sortedByDescending { it.likedBy.size } else if (exploreFilter == "Recent") explorePosts.sortedByDescending { it.createdAt } else explorePosts).chunked(3), key = { row -> row.firstOrNull()?.id ?: "explore-empty" }) { rowPosts ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            rowPosts.forEach { post -> ProfilePostTile(context, post, Modifier.weight(1f)) }
                            repeat(3 - rowPosts.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (pageError.isNotBlank()) item { ErrorLine(pageError) }
                }
            }
            "Reels" -> {
                val reelListState = rememberLazyListState()
                LazyColumn(Modifier.weight(1f), state = reelListState, contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Reels", color = EzoneWhite, fontSize = 25.sp, fontWeight = FontWeight.Black)
                            Text("Short videos from the Ezone community.", color = EzoneMuted, fontSize = 12.sp)
                        }
                    }
                    if (loading && reelPosts.isEmpty()) item { LoadingLine("Loading Reels…") }
                    if (reelPosts.isEmpty() && !loading) item { EmptyState("No Reels yet", "Video posts shared by real members will appear here.") }
                    items(reelPosts, key = { it.id }) { post ->
                        val shouldPlay = reelListState.layoutInfo.visibleItemsInfo.any { it.key == post.id }
                        ReelCard(context, post, myId, shouldPlay && autoplayReels && !dataSaver,
                            onLike = { likePost(context, token, myId, post) { reloadKey++ } },
                            onComments = { activeCommentsPost = post },
                            onProfile = { id -> targetProfileId = id; tab = "Profile" })
                    }
                    if (pageError.isNotBlank()) item { ErrorLine(pageError) }
                }
            }
            "Search" -> {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Search Ezone", color = EzoneMuted) },
                            colors = darkFieldColors()
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val q = searchText.trim().removePrefix("@")
                            if (q.isBlank()) {
                                searchResults = emptyList()
                            } else {
                                loading = true
                                val encoded = Uri.encode(q)
                                EzoneBackend.request(
                                    context, "GET",
                                    "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color,is_private&or=(username.ilike.*" + encoded + "*,display_name.ilike.*" + encoded + "*)&limit=40",
                                    token
                                ) { result ->
                                    loading = false
                                    if (result.ok) searchResults = EzoneBackend.parseProfiles(result.body).filter { it.id != myId }
                                    else pageError = EzoneBackend.errorMessage(result, "Search failed.")
                                }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = EzoneRed, contentColor = Color(0xFF061018))) {
                            Text("Search", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (loading) LoadingLine("Searching…")
                    if (searchResults.isEmpty() && searchText.isNotBlank() && !loading) {
                        EmptyState("No people found", "Check the spelling or try the exact @username.")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                            items(searchResults, key = { it.id }) { item ->
                                ProfileRow(item, item.id in followingIds, item.isPrivate, {
                                    targetProfileId = item.id
                                    tab = "Profile"
                                }, {
                                    val isFollowing = item.id in followingIds
                                    if (isFollowing) {
                                        EzoneBackend.request(context, "DELETE", "/rest/v1/follows?follower_id=eq." + myId + "&following_id=eq." + item.id, token, prefer = "return=minimal") { result ->
                                            if (result.ok) followingIds = followingIds - item.id else pageError = EzoneBackend.errorMessage(result, "Could not unfollow.")
                                        }
                                    } else if (item.isPrivate) {
                                        EzoneBackend.request(context, "GET", "/rest/v1/follow_requests?select=id&follower_id=eq." + myId + "&owner_id=eq." + item.id + "&status=eq.pending&limit=1", token) { pending ->
                                            val exists = pending.ok && try { JSONArray(pending.body).length() > 0 } catch (_: Exception) { false }
                                            if (exists) Toast.makeText(context, "Follow request already sent.", Toast.LENGTH_SHORT).show()
                                            else EzoneBackend.request(context, "POST", "/rest/v1/follow_requests", token, JSONObject().put("follower_id", myId).put("owner_id", item.id), "return=minimal") { result ->
                                                if (result.ok) Toast.makeText(context, "Follow request sent.", Toast.LENGTH_SHORT).show()
                                                else pageError = EzoneBackend.errorMessage(result, "Could not send follow request.")
                                            }
                                        }
                                    } else {
                                        val body = JSONObject().put("follower_id", myId).put("following_id", item.id)
                                        EzoneBackend.request(context, "POST", "/rest/v1/follows", token, body, "return=minimal") { result ->
                                            if (result.ok) followingIds = followingIds + item.id else pageError = EzoneBackend.errorMessage(result, "Could not follow.")
                                        }
                                    }
                                })
                                HorizontalDivider(color = EzoneLine)
                            }
                        }
                    }
                    if (pageError.isNotBlank()) ErrorLine(pageError)
                }
            }
            "Settings" -> {
                EzoneSettingsScreen(
                    context = context,
                    autoplayReels = autoplayReels,
                    autoplayFeedVideos = autoplayFeedVideos,
                    dataSaver = dataSaver,
                    defaultFeed = feedMode,
                    onAutoplayReels = { autoplayReels = it; appPrefs.edit().putBoolean("autoplay_reels", it).apply() },
                    onAutoplayFeedVideos = { autoplayFeedVideos = it; appPrefs.edit().putBoolean("autoplay_feed_videos", it).apply() },
                    onDataSaver = { dataSaver = it; appPrefs.edit().putBoolean("data_saver", it).apply() },
                    onDefaultFeed = { feedMode = it; appPrefs.edit().putString("default_feed", it).apply() },
                    onEditProfile = { targetProfileId = myId; tab = "Profile"; editProfileOpen = true },
                    isPrivateAccount = isPrivateAccount,
                    onPrivateAccountChange = { enabled ->
                        val body = JSONObject().put("is_private", enabled)
                        EzoneBackend.request(context, "PATCH", "/rest/v1/profiles?id=eq." + myId, token, body, "return=minimal") { result ->
                            if (result.ok) isPrivateAccount = enabled
                            else Toast.makeText(context, EzoneBackend.errorMessage(result, "Could not update account privacy."), Toast.LENGTH_LONG).show()
                        }
                    },
                    onFollowRequests = { followRequestsOpen = true },
                    onBlockedUsers = { blockedUsersOpen = true },
                    onSavedPosts = { tab = "Saved" },
                    notifyLikes = notifyLikes,
                    notifyComments = notifyComments,
                    notifyFollows = notifyFollows,
                    notifyMessages = notifyMessages,
                    onNotificationPreferenceChange = { key, enabled ->
                        val body = JSONObject().put("user_id", myId).put(key, enabled)
                        EzoneBackend.request(context, "POST", "/rest/v1/notification_preferences?on_conflict=user_id", token, body, "resolution=merge-duplicates,return=minimal") { result ->
                            if (result.ok) when (key) { "likes" -> notifyLikes = enabled; "comments" -> notifyComments = enabled; "follows" -> notifyFollows = enabled; "messages" -> notifyMessages = enabled }
                            else Toast.makeText(context, EzoneBackend.errorMessage(result, "Could not save notification preference."), Toast.LENGTH_LONG).show()
                        }
                    },
                    onSignOut = { EzoneSession.clear(context); EzoneSession.setRecoveryMode(context, false) }
                )
            }
            "Saved" -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item { Text("Saved posts", color = EzoneWhite, fontSize = 25.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(18.dp)) }
                    if (loading && savedPosts.isEmpty()) item { LoadingLine("Loading saved posts…") }
                    if (!loading && savedPosts.isEmpty()) item { EmptyState("Nothing saved yet", "Tap the bookmark icon on a post to keep it here.") }
                    items(savedPosts, key = { it.id }) { post ->
                        PostCard(context, post, myId, autoplayFeedVideos, dataSaver,
                            onLike = { likePost(context, token, myId, post) { reloadKey++ } },
                            onComments = { activeCommentsPost = post },
                            onProfile = { id -> targetProfileId = id; tab = "Profile" })
                        HorizontalDivider(color = EzoneLine)
                    }
                    if (pageError.isNotBlank()) item { ErrorLine(pageError) }
                }
            }
            "Activity" -> {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        listOf("All", "Mentions", "Replies", "Verified").forEach { filter ->
                            Column(Modifier.weight(1f).clickable { activityFilter = filter }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(filter, color = if (activityFilter == filter) EzoneWhite else EzoneMuted, fontSize = 11.sp, fontWeight = if (activityFilter == filter) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(if (activityFilter == filter) EzoneWhite else Color.Transparent))
                            }
                        }
                    }
                    if (loading && notifications.isEmpty()) LoadingLine("Loading activity…")
                    if (notifications.isEmpty() && !loading) EmptyState("Nothing new yet", "Likes, comments and new followers will appear here.")
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(notifications.filter { n ->
                            activityFilter == "All" ||
                            (activityFilter == "Mentions" && n.type.contains("mention", true)) ||
                            (activityFilter == "Replies" && (n.type.contains("reply", true) || n.type.contains("comment", true))) ||
                            (activityFilter == "Verified" && n.type.contains("verified", true))
                        }, key = { it.id }) { notification ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(EzoneSurface2, CircleShape), contentAlignment = Alignment.Center) {
                                    Text(notification.type.take(1).uppercase(), color = EzoneWhite, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(notification.message, color = EzoneWhite, fontSize = 13.sp)
                                    Text(notification.createdAt.substringBefore("T"), color = EzoneMuted, fontSize = 11.sp)
                                }
                            }
                            HorizontalDivider(color = EzoneLine)
                        }
                    }
                }
            }
            "Profile" -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        if (loading && profile == null) LoadingLine("Loading profile…")
                        else if (profile != null) {
                            ProfileHeader(
                                profile = profile!!,
                                isOwn = targetProfileId == myId,
                                isFollowing = isFollowingTarget,
                                isFollowPending = isFollowPending,
                                onEdit = { editProfileOpen = true },
                                onMessage = { messagesOpen = true; chatUserId = targetProfileId; chatUserName = profile!!.displayName },
                                onFollow = {
                                    when {
                                        isFollowingTarget -> EzoneBackend.request(context, "DELETE", "/rest/v1/follows?follower_id=eq." + myId + "&following_id=eq." + targetProfileId, token, prefer = "return=minimal") { result ->
                                            if (result.ok) isFollowingTarget = false else pageError = EzoneBackend.errorMessage(result, "Could not unfollow.")
                                        }
                                        isFollowPending -> EzoneBackend.request(context, "DELETE", "/rest/v1/follow_requests?follower_id=eq." + myId + "&owner_id=eq." + targetProfileId, token, prefer = "return=minimal") { result ->
                                            if (result.ok) isFollowPending = false else pageError = EzoneBackend.errorMessage(result, "Could not cancel follow request.")
                                        }
                                        profile?.isPrivate == true -> {
                                            val body = JSONObject().put("follower_id", myId).put("owner_id", targetProfileId)
                                            EzoneBackend.request(context, "POST", "/rest/v1/follow_requests", token, body, "return=minimal") { result ->
                                                if (result.ok) isFollowPending = true else pageError = EzoneBackend.errorMessage(result, "Could not send follow request.")
                                            }
                                        }
                                        else -> {
                                            val body = JSONObject().put("follower_id", myId).put("following_id", targetProfileId)
                                            EzoneBackend.request(context, "POST", "/rest/v1/follows", token, body, "return=minimal") { result ->
                                                if (result.ok) isFollowingTarget = true else pageError = EzoneBackend.errorMessage(result, "Could not follow.")
                                            }
                                        }
                                    }
                                },
                                onSignOut = { EzoneSession.clear(context); EzoneSession.setRecoveryMode(context, false) },
                                postCount = profilePosts.size,
                                followerCount = profileFollowerCount,
                                followingCount = profileFollowingCount,
                                onInsights = {
                                    val shareText = "Follow " + (profile?.displayName ?: "me") + " on Ezone (@" + (profile?.username ?: "member") + ")"
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                                    try { context.startActivity(Intent.createChooser(send, "Share profile")) }
                                    catch (_: Exception) { Toast.makeText(context, "Sharing is unavailable on this device.", Toast.LENGTH_SHORT).show() }
                                },
                                onBlock = { profileActionDialog = "block" },
                                onReport = { profileActionDialog = "report" }
                            )
                            HorizontalDivider(color = EzoneLine)
                        } else {
                            if (targetProfileId == myId) {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Set up your Ezone profile", color = EzoneWhite, fontWeight = FontWeight.Bold)
                                    Text("Your profile is being created. If this message stays here, check the Supabase schema and try again.", color = EzoneMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                                    if (pageError.isNotBlank()) ErrorLine(pageError)
                                    Button(onClick = { reloadKey++ }, colors = ButtonDefaults.buttonColors(containerColor = EzoneRed)) {
                                        Text("Retry profile setup", color = EzoneBlack, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else EmptyState("Profile unavailable", "This account may not have finished setting up its profile.")
                        }
                    }
                    item {
                        ProfileGalleryTabs(selected = profileSection, onSelect = { profileSection = it })
                    }
                    items(visibleProfilePosts.chunked(3), key = { row -> row.firstOrNull()?.id ?: "empty" }) { rowPosts ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowPosts.forEach { post ->
                                ProfilePostTile(
                                    context, post, Modifier.weight(1f),
                                    pinned = targetProfileId == myId && post.id in pinnedPostIds,
                                    canPin = targetProfileId == myId,
                                    onPin = {
                                        val isPinned = post.id in pinnedPostIds
                                        if (isPinned) {
                                            EzoneBackend.request(context, "DELETE", "/rest/v1/profile_post_pins?user_id=eq." + myId + "&post_id=eq." + post.id, token, prefer = "return=minimal") { result ->
                                                if (result.ok) pinnedPostIds = pinnedPostIds - post.id else pageError = EzoneBackend.errorMessage(result, "Could not unpin post.")
                                            }
                                        } else {
                                            val payload = JSONObject().put("user_id", myId).put("post_id", post.id)
                                            EzoneBackend.request(context, "POST", "/rest/v1/profile_post_pins", token, payload, "return=minimal") { result ->
                                                if (result.ok) pinnedPostIds = pinnedPostIds + post.id else pageError = EzoneBackend.errorMessage(result, "Could not pin post.")
                                            }
                                        }
                                    }
                                )
                            }
                            repeat(3 - rowPosts.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (profilePosts.isEmpty() && !loading) item { EmptyState(if (profile?.isPrivate == true && !isFollowingTarget && targetProfileId != myId) "This account is private" else "No posts yet", if (profile?.isPrivate == true && !isFollowingTarget && targetProfileId != myId) "Follow this account and wait for approval to see their posts." else "Your photos and videos will appear here.") }
                    if (pageError.isNotBlank()) item { ErrorLine(pageError) }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF242424))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().background(EzoneBlack).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Home", "Explore", "Create", "Activity", "Profile").forEach { item ->
                val selected = tab == item
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { contentDescription = "$item tab" }
                        .clickable {
                            tab = item
                            if (item == "Profile") targetProfileId = myId
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        when (item) { "Home" -> "⌂"; "Explore" -> "⌕"; "Create" -> "⊞"; "Activity" -> "♡"; else -> "♙" },
                        color = if (selected) EzoneWhite else Color(0xFF8B8B8B),
                        fontSize = if (item == "Create") 25.sp else 24.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }

    if (selectedStory != null) StoryViewer(context, selectedStory!!, onDismiss = { selectedStory = null })
    if (followRequestsOpen) FollowRequestsDialog(context, onDismiss = { followRequestsOpen = false }, onChanged = { reloadKey++ })
    if (blockedUsersOpen) BlockedUsersDialog(context, onDismiss = { blockedUsersOpen = false })
    if (messagesOpen) {
        MessagesScreen(context, onOpenChat = { id, name -> chatUserId = id; chatUserName = name; messagesOpen = false })
    }
    if (chatUserId.isNotBlank()) {
        DirectChatDialog(context, chatUserId, chatUserName, onDismiss = { chatUserId = "" })
    }
    if (activeCommentsPost != null) {
        CommentsDialog(
            context = context,
            post = activeCommentsPost!!,
            onDismiss = { activeCommentsPost = null },
            onChanged = { reloadKey++ }
        )
    }
    if (showProfileInsights && profile != null && targetProfileId == myId) {
        AlertDialog(
            onDismissRequest = { showProfileInsights = false },
            containerColor = EzoneSurface,
            title = { Text("Account insights", color = EzoneWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Posts published: ${profilePosts.size}", color = EzoneWhite)
                    Text("Likes received: ${profilePosts.sumOf { it.likedBy.size }}", color = EzoneWhite)
                    Text("Followers: $profileFollowerCount", color = EzoneWhite)
                    Text("Following: $profileFollowingCount", color = EzoneWhite)
                    Text("These are profile totals currently available. Reach, impressions and watch-time analytics are not collected yet.", color = EzoneMuted, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showProfileInsights = false }) { Text("Close", color = EzoneRed) } }
        )
    }
    if (profileActionDialog == "block") {
        AlertDialog(
            onDismissRequest = { profileActionDialog = "" },
            containerColor = EzoneSurface,
            title = { Text("Block @${profile?.username.orEmpty()}?", color = EzoneWhite) },
            text = { Text("This account will be added to your blocked list.", color = EzoneMuted) },
            confirmButton = {
                TextButton(onClick = {
                    val payload = JSONObject().put("blocker_id", myId).put("blocked_id", targetProfileId)
                    EzoneBackend.request(context, "POST", "/rest/v1/blocked_users", token, payload, "resolution=ignore-duplicates,return=minimal") { result ->
                        if (result.ok) { profileActionDialog = ""; tab = "Home" }
                        else pageError = EzoneBackend.errorMessage(result, "Could not block this account.")
                    }
                }) { Text("Block", color = EzoneRed) }
            },
            dismissButton = { TextButton(onClick = { profileActionDialog = "" }) { Text("Cancel", color = EzoneMuted) } }
        )
    }
    if (profileActionDialog == "report") {
        AlertDialog(
            onDismissRequest = { profileActionDialog = "" },
            containerColor = EzoneSurface,
            title = { Text("Report this account", color = EzoneWhite) },
            text = {
                Column {
                    listOf("spam", "harassment", "nudity", "violence", "scam", "other").forEach { reason ->
                        Row(Modifier.fillMaxWidth().clickable { reportReason = reason }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reportReason == reason, onClick = { reportReason = reason }, colors = RadioButtonDefaults.colors(selectedColor = EzoneRed))
                            Text(reason.replaceFirstChar { it.uppercase() }, color = EzoneWhite)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val payload = JSONObject().put("reporter_id", myId).put("reported_user_id", targetProfileId).put("reason", reportReason)
                    EzoneBackend.request(context, "POST", "/rest/v1/content_reports", token, payload, "return=minimal") { result ->
                        if (result.ok) {
                            profileActionDialog = ""
                            Toast.makeText(context, "Report submitted.", Toast.LENGTH_SHORT).show()
                        } else pageError = EzoneBackend.errorMessage(result, "Could not submit report.")
                    }
                }) { Text("Submit report", color = EzoneRed) }
            },
            dismissButton = { TextButton(onClick = { profileActionDialog = "" }) { Text("Cancel", color = EzoneMuted) } }
        )
    }
    if (editProfileOpen && profile != null && targetProfileId == myId) {
        EditProfileDialog(
            profile = profile!!,
            context = context,
            onDismiss = { editProfileOpen = false },
            onSaved = {
                editProfileOpen = false
                reloadKey++
            }
        )
    }
    if (recoveryDialogOpen) {
        ChangePasswordDialog(
            context = context,
            onDismiss = { recoveryDialogOpen = false; EzoneSession.setRecoveryMode(context, false) }
        )
    }
}

@Composable
private fun EzoneSettingsScreen(
    context: Context,
    autoplayReels: Boolean,
    autoplayFeedVideos: Boolean,
    dataSaver: Boolean,
    defaultFeed: String,
    onAutoplayReels: (Boolean) -> Unit,
    onAutoplayFeedVideos: (Boolean) -> Unit,
    onDataSaver: (Boolean) -> Unit,
    onDefaultFeed: (String) -> Unit,
    onEditProfile: () -> Unit,
    isPrivateAccount: Boolean,
    onPrivateAccountChange: (Boolean) -> Unit,
    onFollowRequests: () -> Unit,
    onBlockedUsers: () -> Unit,
    onSavedPosts: () -> Unit,
    notifyLikes: Boolean,
    notifyComments: Boolean,
    notifyFollows: Boolean,
    notifyMessages: Boolean,
    onNotificationPreferenceChange: (String, Boolean) -> Unit,
    onSignOut: () -> Unit
) {
    var passwordDialog by remember { mutableStateOf(false) }
    var ownProfile by remember { mutableStateOf<EzoneProfile?>(null) }
    LaunchedEffect(Unit) {
        EzoneBackend.request(context, "GET",
            "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color,is_private&id=eq." + EzoneSession.userId + "&limit=1",
            EzoneSession.accessToken) { result ->
            if (result.ok) ownProfile = EzoneBackend.parseProfiles(result.body).firstOrNull()
        }
    }
    Column(Modifier.fillMaxSize().background(EzoneBlack)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(EzoneSurface).clickable(onClick = onEditProfile).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(ownProfile?.displayName?.take(1)?.uppercase() ?: EzoneSession.email.substringBefore("@").take(1).uppercase().ifBlank { "E" }, 52, ownProfile?.avatarUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ownProfile?.displayName?.ifBlank { "Ezone member" } ?: EzoneSession.email.substringBefore("@"), color = EzoneWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("@${ownProfile?.username ?: EzoneSession.email.substringBefore("@")}", color = EzoneMuted, fontSize = 10.sp)
                    Text("View profile", color = EzoneMuted, fontSize = 10.sp)
                }
                Text("›", color = EzoneWhite, fontSize = 22.sp)
            }
            Spacer(Modifier.height(12.dp))
            SettingsSection("Account")
            SettingsAction("Edit profile", "Name, username, bio and profile photo", "›", onEditProfile)
            SettingsAction("Change password", "Update your account password", "›", { passwordDialog = true })
            SettingsAction("Sign out", "Log out of this device", "↗", onSignOut, danger = true)
            SettingsSection("Your feed")
            SettingsChoice("Default feed", "Choose which posts open first", listOf("For you", "Following"), defaultFeed, onDefaultFeed)
            SettingsSection("Data usage")
            SettingsToggle("Autoplay Reels", "Play visible short videos automatically", autoplayReels, onAutoplayReels)
            SettingsToggle("Autoplay feed videos", "Start videos while scrolling your feed", autoplayFeedVideos, onAutoplayFeedVideos)
            SettingsToggle("Data saver", "Disable automatic video playback to reduce data use", dataSaver, onDataSaver)
            SettingsSection("Privacy")
            SettingsToggle("Private account", "Only approved followers can see your posts and Stories.", isPrivateAccount, onPrivateAccountChange)
            SettingsAction("Follow requests", "Review people asking to follow you", "›", onFollowRequests)
            SettingsAction("Blocked accounts", "Review or unblock accounts", "›", onBlockedUsers)
            SettingsAction("Saved posts", "Open the posts you bookmarked", "›", onSavedPosts)
            SettingsToggle("Likes", "Notify me when someone likes my posts", notifyLikes) { onNotificationPreferenceChange("likes", it) }
            SettingsToggle("Comments", "Notify me when someone comments on my posts", notifyComments) { onNotificationPreferenceChange("comments", it) }
            SettingsToggle("New followers", "Notify me when someone follows me", notifyFollows) { onNotificationPreferenceChange("follows", it) }
            SettingsToggle("Messages", "Notify me about new direct messages", notifyMessages) { onNotificationPreferenceChange("messages", it) }
            SettingsInfo("Push notifications", "These preferences control in-app activity records. Push delivery still needs notification credentials and device registration.")
            SettingsSection("About Ezone")
            SettingsInfo("Ezone", "A social space for photos, videos, short-form clips and conversations.")
            SettingsInfo("Connection", "Connected to your configured Ezone backend. Never share your account password or private server keys.")
            Spacer(Modifier.height(26.dp))
        }
    }
    if (passwordDialog) ChangePasswordDialog(context, onDismiss = { passwordDialog = false })
}

@Composable
private fun BlockedUsersDialog(context: Context, onDismiss: () -> Unit) {
    var blockedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var blockedProfiles by remember { mutableStateOf<List<EzoneProfile>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        EzoneBackend.request(context, "GET", "/rest/v1/blocked_users?select=blocked_id&blocker_id=eq." + EzoneSession.userId, EzoneSession.accessToken) { result ->
            if (!result.ok) message = EzoneBackend.errorMessage(result, "Could not load blocked accounts.")
            else {
                val arr = try { JSONArray(result.body) } catch (_: Exception) { JSONArray() }
                blockedIds = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("blocked_id") }.filter { it.isNotBlank() }
                if (blockedIds.isEmpty()) blockedProfiles = emptyList()
                else EzoneBackend.request(context, "GET", "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color,is_private&id=in.(" + blockedIds.joinToString(",") + ")", EzoneSession.accessToken) { profiles ->
                    if (profiles.ok) blockedProfiles = EzoneBackend.parseProfiles(profiles.body)
                    else message = EzoneBackend.errorMessage(profiles, "Could not load blocked profiles.")
                }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(EzoneSurface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Blocked accounts", color = EzoneWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close", color = EzoneMuted) }
            }
            if (message.isNotBlank()) ErrorLine(message)
            if (blockedProfiles.isEmpty() && message.isBlank()) EmptyState("No blocked accounts", "Accounts you block will appear here.")
            blockedProfiles.forEach { person ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(person.displayName.take(1).uppercase(), 38, person.avatarUrl)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(person.displayName, color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("@${person.username}", color = EzoneMuted, fontSize = 10.sp)
                    }
                    TextButton(enabled = busyId.isBlank(), onClick = {
                        busyId = person.id
                        EzoneBackend.request(context, "DELETE", "/rest/v1/blocked_users?blocker_id=eq." + EzoneSession.userId + "&blocked_id=eq." + person.id, EzoneSession.accessToken, prefer = "return=minimal") { result ->
                            busyId = ""
                            if (result.ok) {
                                blockedProfiles = blockedProfiles.filterNot { it.id == person.id }
                                blockedIds = blockedIds.filterNot { it == person.id }
                            } else message = EzoneBackend.errorMessage(result, "Could not unblock this account.")
                        }
                    }) { Text("Unblock", color = EzoneRed, fontSize = 11.sp) }
                }
                HorizontalDivider(color = EzoneLine)
            }
        }
    }
}

@Composable
private fun FollowRequestsDialog(context: Context, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var requests by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loadingRequests by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    fun loadRequests() {
        loadingRequests = true
        EzoneBackend.request(
            context, "GET",
            "/rest/v1/follow_requests?select=id,follower_id,created_at,profiles:profiles!follow_requests_follower_id_fkey(id,username,display_name,avatar_url)&owner_id=eq." + EzoneSession.userId + "&status=eq.pending&order=created_at.desc",
            EzoneSession.accessToken
        ) { result ->
            loadingRequests = false
            if (result.ok) {
                val arr = try { JSONArray(result.body) } catch (_: Exception) { JSONArray() }
                requests = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                message = ""
            } else message = EzoneBackend.errorMessage(result, "Could not load follow requests.")
        }
    }
    LaunchedEffect(Unit) { loadRequests() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(EzoneSurface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Follow requests", color = EzoneWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close", color = EzoneMuted) }
            }
            if (loadingRequests) LoadingLine("Loading requests…")
            if (!loadingRequests && requests.isEmpty()) EmptyState("No pending requests", "New requests from private-account followers will appear here.")
            if (message.isNotBlank()) ErrorLine(message)
            requests.forEach { request ->
                val person = request.optJSONObject("profiles")
                val id = request.optString("id")
                val followerId = request.optString("follower_id")
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(person?.optString("display_name")?.take(1)?.uppercase() ?: "E", 40, person?.optString("avatar_url"))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(person?.optString("display_name") ?: "Ezone member", color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("@"+(person?.optString("username") ?: "member"), color = EzoneMuted, fontSize = 10.sp)
                    }
                    TextButton(onClick = {
                        EzoneBackend.request(context, "PATCH", "/rest/v1/follow_requests?id=eq."+id+"&owner_id=eq."+EzoneSession.userId,
                            EzoneSession.accessToken, JSONObject().put("status","declined"), "return=minimal") { result ->
                            if (result.ok) { requests = requests.filterNot { it.optString("id") == id }; onChanged() }
                            else message = EzoneBackend.errorMessage(result, "Could not decline request.")
                        }
                    }) { Text("Decline", color = EzoneMuted, fontSize = 10.sp) }
                    TextButton(onClick = {
                        EzoneBackend.request(context, "PATCH", "/rest/v1/follow_requests?id=eq."+id+"&owner_id=eq."+EzoneSession.userId,
                            EzoneSession.accessToken, JSONObject().put("status","accepted"), "return=minimal") { result ->
                            if (result.ok) { requests = requests.filterNot { it.optString("id") == id }; onChanged() }
                            else message = EzoneBackend.errorMessage(result, "Could not approve request.")
                        }
                    }) { Text("Accept", color = EzoneRed, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = EzoneLine)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 17.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.lowercase().replaceFirstChar { it.uppercase() }, color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(color = EzoneLine)
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String, trailing: String, onClick: () -> Unit, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(EzoneSurface).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(when(title) {
            "Edit profile" -> "♙"; "Change password" -> "▣"; "Sign out" -> "↗"
            "Follow requests" -> "♧"; "Blocked accounts" -> "⊘"; "Saved posts" -> "▤"
            "Account" -> "♙"; "Privacy" -> "♧"; "Notifications" -> "♧"
            "Appearance" -> "◐"; "Data usage" -> "⌁"; "Help" -> "?"; "About Ezone" -> "ⓘ"
            else -> "♙"
        }, color = EzoneWhite, fontSize = 18.sp, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = EzoneWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = EzoneMuted, fontSize = 10.sp)
        }
        Text(trailing, color = EzoneMuted, fontSize = 20.sp)
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(EzoneSurface).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = EzoneWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = EzoneMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF050505), checkedTrackColor = Color.White))
    }
}

@Composable
private fun SettingsChoice(title: String, subtitle: String, options: List<String>, selected: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(EzoneSurface).padding(horizontal = 14.dp, vertical = 13.dp)) {
        Text(title, color = EzoneWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = EzoneMuted, fontSize = 10.sp)
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(EzoneSurface2).padding(3.dp)) {
            options.forEach { option ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (selected == option) Color(0xFF303030) else Color.Transparent)
                    .clickable { onChange(option) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text(option, color = if (selected == option) EzoneWhite else EzoneMuted, fontSize = 11.sp, fontWeight = if (selected == option) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun SettingsInfo(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(EzoneSurface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, color = EzoneWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = EzoneMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun StoriesRail(context: Context, refreshKey: Int, onStoryPosted: () -> Unit, onOpenStory: (JSONObject) -> Unit, onOpenProfile: (String) -> Unit) {
    var stories by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val token = EzoneSession.accessToken
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (!mime.startsWith("image/") && !mime.startsWith("video/")) { note = "Choose an image or video for your Story." ; return@rememberLauncherForActivityResult }
            busy = true
            val type = if (mime.startsWith("video/")) "video" else "image"
            val ext = if (type == "video") "mp4" else if (mime.contains("png")) "png" else "jpg"
            val path = EzoneSession.userId + "/story_" + System.currentTimeMillis() + "." + ext
            EzoneBackend.upload(context, uri, path, token) { uploaded ->
                if (!uploaded.ok) { busy = false; note = EzoneBackend.errorMessage(uploaded, "Story upload failed.") }
                else {
                    val body = JSONObject().put("user_id", EzoneSession.userId).put("media_path", path).put("media_type", type).put("audience", "public")
                    EzoneBackend.request(context, "POST", "/rest/v1/stories", token, body, "return=minimal") { created ->
                        busy = false
                        if (created.ok) { note = "Story shared for 24 hours."; onStoryPosted() }
                        else note = EzoneBackend.errorMessage(created, "Could not publish story. Run the latest Supabase schema.")
                    }
                }
            }
        }
    }
    LaunchedEffect(EzoneSession.userId, refreshKey) {
        EzoneBackend.request(context, "GET", "/rest/v1/stories?select=id,user_id,media_path,media_type,caption,created_at,profiles:profiles!stories_user_id_fkey(id,username,display_name,avatar_url)&expires_at=gt." + Uri.encode(java.time.Instant.now().toString()) + "&order=created_at.desc&limit=40", token) { result ->
            if (result.ok) {
                val arr = try { JSONArray(result.body) } catch (_: Exception) { JSONArray() }
                stories = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } else note = EzoneBackend.errorMessage(result, "Could not load Stories. Run the latest schema.")
        }
    }
    Column(Modifier.fillMaxWidth().background(EzoneSurface).padding(top = 11.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STORIES", color = EzoneWhite, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.6.sp)
            Spacer(Modifier.weight(1f))
            Text(if (busy) "Uploading…" else "24 HOURS", color = EzoneMuted, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clickable(enabled = !busy) { picker.launch("*/*") }) {
                    Box(Modifier.size(58.dp).clip(CircleShape).background(EzoneSurface2), contentAlignment = Alignment.Center) {
                        Avatar(EzoneSession.email.take(1).uppercase(), 54)
                        Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(EzoneRed), contentAlignment = Alignment.Center) { Text("+", color = Color(0xFF090909), fontWeight = FontWeight.Black) }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Your story", color = EzoneMuted, fontSize = 10.sp, maxLines = 1)
                }
            }
            items(stories) { story ->
                val owner = story.optJSONObject("profiles")
                val username = owner?.optString("username").orEmpty().ifBlank { "Member" }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clickable {
                    onOpenStory(story)
                }) {
                    Box(Modifier.size(58.dp).clip(CircleShape).background(Brush.linearGradient(listOf(EzoneRed, Color(0xFF383838)))).padding(3.dp)) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(EzoneBlack), contentAlignment = Alignment.Center) { Avatar(username.take(1).uppercase(), 48, owner?.optString("avatar_url")) }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(username, color = EzoneMuted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        if (note.isNotBlank()) Text(note, color = EzoneRed, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}


@Composable
private fun StoryViewer(context: Context, story: JSONObject, onDismiss: () -> Unit) {
    val mediaUrl = EzoneBackend.mediaUrl(context, story.optString("media_path"))
    val owner = story.optJSONObject("profiles")
    var reply by remember(story.optString("id")) { mutableStateOf("") }
    var sendingReply by remember(story.optString("id")) { mutableStateOf(false) }
    var replyNotice by remember(story.optString("id")) { mutableStateOf("") }
    LaunchedEffect(story.optString("id")) {
        val ownerId = story.optString("user_id")
        if (ownerId.isNotBlank() && ownerId != EzoneSession.userId) {
            val view = JSONObject().put("story_id", story.optString("id")).put("viewer_id", EzoneSession.userId)
            EzoneBackend.request(context, "POST", "/rest/v1/story_views", EzoneSession.accessToken, view, "resolution=merge-duplicates,return=minimal") { }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (story.optString("media_type") == "video") {
                EzoneVideoPlayer(context, mediaUrl, Modifier.fillMaxSize(), loop = false, showController = true, autoPlay = true)
            } else {
                AsyncImage(model = mediaUrl, contentDescription = "Story", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))).statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(owner?.optString("username")?.take(1)?.uppercase() ?: "E", 38, owner?.optString("avatar_url"))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(owner?.optString("display_name").orEmpty().ifBlank { owner?.optString("username").orEmpty().ifBlank { "Ezone member" } }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(story.optString("created_at").substringBefore("T"), color = Color.LightGray, fontSize = 10.sp)
                }
                TextButton(onClick = onDismiss) { Text("✕", color = Color.White, fontSize = 22.sp) }
            }
            if (owner?.optString("id").orEmpty().isNotBlank() && owner?.optString("id") != EzoneSession.userId) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("❤️", "😂", "🔥", "👏").forEach { emoji ->
                            TextButton(enabled = !sendingReply, onClick = {
                                sendingReply = true
                                val payload = JSONObject().put("sender_id", EzoneSession.userId)
                                    .put("receiver_id", story.optString("user_id"))
                                    .put("body", emoji + " Replied to your story")
                                EzoneBackend.request(context, "POST", "/rest/v1/direct_messages",
                                    EzoneSession.accessToken, payload, "return=minimal") { result ->
                                    sendingReply = false
                                    replyNotice = if (result.ok) "Reaction sent" else EzoneBackend.errorMessage(result, "Could not send reaction.")
                                }
                            }) { Text(emoji, fontSize = 22.sp) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = reply, onValueChange = { reply = it.take(1000) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("Reply to story…", color = Color.LightGray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = EzoneRed, unfocusedBorderColor = Color.Gray,
                                focusedContainerColor = Color(0x55000000), unfocusedContainerColor = Color(0x55000000)
                            ))
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = reply.trim().isNotEmpty() && !sendingReply, onClick = {
                            sendingReply = true
                            val payload = JSONObject().put("sender_id", EzoneSession.userId)
                                .put("receiver_id", story.optString("user_id")).put("body", reply.trim())
                            EzoneBackend.request(context, "POST", "/rest/v1/direct_messages",
                                EzoneSession.accessToken, payload, "return=minimal") { result ->
                                sendingReply = false
                                if (result.ok) { reply = ""; replyNotice = "Reply sent to messages" }
                                else replyNotice = EzoneBackend.errorMessage(result, "Could not send reply.")
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = EzoneRed, contentColor = Color.White)) {
                            Text(if (sendingReply) "…" else "Send")
                        }
                    }
                    if (replyNotice.isNotBlank()) Text(replyNotice, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun EzoneVideoPlayer(context: Context, url: String, modifier: Modifier = Modifier, loop: Boolean = false, showController: Boolean = true, autoPlay: Boolean = false) {
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = autoPlay
            prepare()
        }
    }
    LaunchedEffect(player, autoPlay) { player.playWhenReady = autoPlay }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = showController } },
        update = { view -> view.player = player; view.useController = showController }
    )
}

@Composable
private fun ReelCard(context: Context, post: EzonePost, myId: String, autoPlay: Boolean, onLike: () -> Unit, onComments: () -> Unit, onProfile: (String) -> Unit) {
    val author = post.author
    val liked = myId in post.likedBy
    Box(Modifier.fillMaxWidth().height(620.dp).background(Color.Black)) {
        if (!post.mediaPath.isNullOrBlank()) {
            EzoneVideoPlayer(
                context, EzoneBackend.mediaUrl(context, post.mediaPath),
                Modifier.fillMaxSize(), loop = true, showController = true, autoPlay = autoPlay
            )
        } else {
            Box(Modifier.fillMaxSize().background(EzoneSurface2), contentAlignment = Alignment.Center) {
                Text("This Reel has no video attached.", color = EzoneMuted, fontSize = 13.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 15.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("REELS", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.7.sp,
                modifier = Modifier.weight(1f))
            Text("● LIVE FEED", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.78f).padding(start = 16.dp, end = 10.dp, bottom = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { author?.id?.let(onProfile) }) {
                Avatar(author?.displayName?.take(1)?.uppercase() ?: "E", 38, author?.avatarUrl)
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(author?.displayName ?: "Ezone member", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("@${author?.username ?: "member"}", color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text("View profile", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x55FFFFFF)).clickable { author?.id?.let(onProfile) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (post.body.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(post.body, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3)
            }
            Spacer(Modifier.height(6.dp))
            Text("♫  Original audio · Ezone", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 27.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onLike, contentPadding = PaddingValues(2.dp)) {
                    Text(if (liked) "♥" else "♡", color = if (liked) Color(0xFFFFFFFF) else Color.White, fontSize = 29.sp)
                }
                Text(post.likedBy.size.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onComments, contentPadding = PaddingValues(2.dp)) { Text("◯", color = Color.White, fontSize = 26.sp) }
                Text("Comment", color = Color.White, fontSize = 9.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = {
                    val text = buildString {
                        append(post.body)
                        if (!post.mediaPath.isNullOrBlank()) {
                            if (isNotBlank()) append("\n")
                            append(EzoneBackend.mediaUrl(context, post.mediaPath))
                        }
                    }.ifBlank { "Watch this Ezone Reel" }
                    try {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share Reel"))
                    } catch (_: Exception) { Toast.makeText(context, "Sharing is unavailable.", Toast.LENGTH_SHORT).show() }
                }, contentPadding = PaddingValues(2.dp)) { Text("↗", color = Color.White, fontSize = 26.sp) }
                Text("Share", color = Color.White, fontSize = 9.sp)
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    } catch (_: Exception) { "" }
}

@Composable
private fun PostComposer(context: Context, onPosted: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    var attachmentType by remember { mutableStateOf("image") }
    var musicAttachment by remember { mutableStateOf<Uri?>(null) }
    var musicTitle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val token = EzoneSession.accessToken
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { attachment = uri; attachmentType = "image"; message = "" }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { attachment = uri; attachmentType = "video"; message = "" }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { musicAttachment = uri; musicTitle = queryDisplayName(context, uri).ifBlank { "Selected audio" }; message = "" }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(22.dp)).background(EzoneSurface).padding(15.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(EzoneSession.email.substringBefore("@").take(1).uppercase().ifBlank { "E" }, 42)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(EzoneSession.email.substringBefore("@").ifBlank { "Your profile" }, color = EzoneWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("What's on your mind?", color = EzoneMuted) },
                    minLines = 2,
                    maxLines = 6,
                    colors = darkFieldColors()
                )
            }
        }
        if (attachment != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (attachmentType == "image") {
                    AsyncImage(
                        model = attachment,
                        contentDescription = "Selected photo",
                        modifier = Modifier.size(104.dp).clip(RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.size(104.dp).clip(RoundedCornerShape(13.dp)).background(EzoneSurface2), contentAlignment = Alignment.Center) {
                        Text("▶ VIDEO", color = EzoneRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { attachment = null }) { Text("Remove", color = Color(0xFFBDBDBD), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = { photoPicker.launch("image/*") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("▧", color = EzoneWhite, fontSize = 19.sp) }
            TextButton(enabled = !busy, onClick = { videoPicker.launch("video/*") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("GIF", color = EzoneWhite, fontSize = 12.sp) }
            TextButton(enabled = !busy, onClick = { audioPicker.launch("audio/*") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("♫", color = EzoneWhite, fontSize = 19.sp) }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy && (draft.trim().isNotEmpty() || attachment != null || musicAttachment != null),
                onClick = {
                    busy = true
                    message = ""
                    fun createPost(mediaPath: String?, mediaType: String?, audioPath: String?) {
                        val body = JSONObject().put("user_id", EzoneSession.userId).put("body", draft.trim())
                            .put("media_path", mediaPath ?: JSONObject.NULL).put("media_type", mediaType ?: JSONObject.NULL)
                            .put("music_path", audioPath ?: JSONObject.NULL).put("music_title", musicTitle.takeIf { audioPath != null } ?: JSONObject.NULL)
                            .put("music_artist", JSONObject.NULL)
                        EzoneBackend.request(context, "POST", "/rest/v1/posts", token, body, "return=representation") { result ->
                            busy = false
                            if (result.ok) { draft = ""; attachment = null; musicAttachment = null; musicTitle = ""; message = ""; onPosted() }
                            else message = EzoneBackend.errorMessage(result, "Your post could not be published.")
                        }
                    }
                    fun uploadAudio(mediaPath: String?, mediaType: String?) {
                        val selectedAudio = musicAttachment
                        if (selectedAudio == null) createPost(mediaPath, mediaType, null) else {
                            val mime = context.contentResolver.getType(selectedAudio).orEmpty()
                            val ext = when { mime.contains("mpeg") -> "mp3"; mime.contains("wav") -> "wav"; mime.contains("ogg") -> "ogg"; else -> "m4a" }
                            val audioPath = EzoneSession.userId + "/audio_" + System.currentTimeMillis() + "." + ext
                            EzoneBackend.upload(context, selectedAudio, audioPath, token) { uploaded ->
                                if (uploaded.ok) createPost(mediaPath, mediaType, audioPath)
                                else { busy = false; message = EzoneBackend.errorMessage(uploaded, "Audio upload failed.") }
                            }
                        }
                    }
                    val selected = attachment
                    if (selected == null) {
                        uploadAudio(null, null)
                    } else {
                        val extension = if (attachmentType == "video") "mp4" else {
                            when (context.contentResolver.getType(selected)?.lowercase()) {
                                "image/png" -> "png"
                                "image/webp" -> "webp"
                                else -> "jpg"
                            }
                        }
                        val remotePath = EzoneSession.userId + "/" + System.currentTimeMillis() + "." + extension
                        EzoneBackend.upload(context, selected, remotePath, token) { result ->
                            if (result.ok) uploadAudio(remotePath, attachmentType)
                            else {
                                busy = false
                                message = EzoneBackend.errorMessage(result, "Media upload failed.")
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EzoneRed, contentColor = Color(0xFF061018), disabledContainerColor = Color(0xFF242424)),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 7.dp)
            ) {
                Text(if (busy) "Posting…" else "Post", fontWeight = FontWeight.Bold)
            }
        }
        if (message.isNotBlank()) Text(message, color = Color(0xFFBDBDBD), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PostCard(
    context: Context,
    post: EzonePost,
    myId: String,
    autoplayFeedVideos: Boolean,
    dataSaver: Boolean,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onProfile: (String) -> Unit
) {
    val author = post.author
    val liked = myId in post.likedBy
    var saved by remember(post.id, myId) { mutableStateOf(false) }
    var reposted by remember(post.id, myId) { mutableStateOf(false) }
    var actionBusy by remember(post.id, myId) { mutableStateOf(false) }
    var reportOpen by remember(post.id) { mutableStateOf(false) }
    var reportReason by remember(post.id) { mutableStateOf("spam") }

    LaunchedEffect(post.id, myId) {
        EzoneBackend.request(context, "GET",
            "/rest/v1/saved_posts?select=post_id&user_id=eq." + myId + "&post_id=eq." + post.id + "&limit=1",
            EzoneSession.accessToken) { result ->
            if (result.ok) saved = try { JSONArray(result.body).length() > 0 } catch (_: Exception) { false }
        }
        EzoneBackend.request(context, "GET",
            "/rest/v1/post_reposts?select=id&user_id=eq." + myId + "&post_id=eq." + post.id + "&limit=1",
            EzoneSession.accessToken) { result ->
            if (result.ok) reposted = try { JSONArray(result.body).length() > 0 } catch (_: Exception) { false }
        }
    }

    Column(Modifier.fillMaxWidth().background(EzoneBlack).padding(horizontal = 15.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(author?.displayName?.take(1)?.uppercase() ?: "E", 30, author?.avatarUrl)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(author?.username ?: "ezone.member", color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { author?.id?.let(onProfile) })
                    Text("  ·  " + post.createdAt.substringBefore("T"), color = EzoneMuted, fontSize = 10.sp)
                }
            }
            Text("•••", color = EzoneMuted, fontSize = 16.sp)
        }
        if (post.body.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(post.body, color = EzoneWhite, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (!post.mediaPath.isNullOrBlank()) {
            val mediaUrl = EzoneBackend.mediaUrl(context, post.mediaPath)
            Spacer(Modifier.height(10.dp))
            if (post.mediaType == "image") {
                AsyncImage(
                    model = mediaUrl,
                    contentDescription = "Post photo",
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.82f).pointerInput(post.id, liked) { detectTapGestures(onDoubleTap = { if (!liked) onLike() }) },
                    contentScale = ContentScale.Crop
                )
            } else {
                EzoneVideoPlayer(
                    context = context,
                    url = mediaUrl,
                    modifier = Modifier.fillMaxWidth().height(330.dp),
                    loop = false,
                    showController = true,
                    autoPlay = autoplayFeedVideos && !dataSaver
                )
            }
        }
        if (!post.musicPath.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().padding(top = 9.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF202020))
                .clickable {
                    val audioUrl = EzoneBackend.mediaUrl(context, post.musicPath)
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(audioUrl), "audio/*")) }
                    catch (_: Exception) { Toast.makeText(context, "No audio player found.", Toast.LENGTH_SHORT).show() }
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF252525)), contentAlignment = Alignment.Center) { Text("♫", color = EzoneRed, fontSize = 20.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(post.musicTitle ?: "Audio track", color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(post.musicArtist ?: "Ezone audio", color = EzoneMuted, fontSize = 10.sp) }
                Text("▶", color = EzoneRed, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLike, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) {
                Text((if (liked) "♥" else "♡") + "  " + post.likedBy.size, color = if (liked) Color(0xFFFFFFFF) else EzoneWhite, fontSize = 16.sp)
            }
            TextButton(onClick = onComments, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) { Text("▢  Comment", color = EzoneWhite, fontSize = 11.sp) }
            TextButton(enabled = !actionBusy, onClick = {
                actionBusy = true
                val method = if (saved) "DELETE" else "POST"
                val path = "/rest/v1/saved_posts?user_id=eq." + myId + "&post_id=eq." + post.id
                val payload = if (saved) null else JSONObject().put("user_id", myId).put("post_id", post.id)
                EzoneBackend.request(context, method, path, EzoneSession.accessToken, payload, "return=minimal") { result ->
                    actionBusy = false
                    if (result.ok) {
                        saved = !saved
                        Toast.makeText(context, if (saved) "Post saved" else "Removed from saved posts", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(context, EzoneBackend.errorMessage(result, "Could not update saved posts."), Toast.LENGTH_LONG).show()
                }
            }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) {
                Text(if (saved) "▣  Saved" else "▢  Save", color = if (saved) EzoneRed else EzoneWhite, fontSize = 11.sp)
            }
            TextButton(enabled = !actionBusy, onClick = {
                actionBusy = true
                if (reposted) {
                    EzoneBackend.request(context, "DELETE",
                        "/rest/v1/post_reposts?user_id=eq." + myId + "&post_id=eq." + post.id,
                        EzoneSession.accessToken, prefer = "return=minimal") { result ->
                        actionBusy = false
                        if (result.ok) reposted = false else Toast.makeText(context, EzoneBackend.errorMessage(result, "Could not undo repost."), Toast.LENGTH_LONG).show()
                    }
                } else {
                    val payload = JSONObject().put("user_id", myId).put("post_id", post.id)
                    EzoneBackend.request(context, "POST", "/rest/v1/post_reposts", EzoneSession.accessToken, payload, "return=minimal") { result ->
                        actionBusy = false
                        if (result.ok) reposted = true else Toast.makeText(context, EzoneBackend.errorMessage(result, "Could not repost."), Toast.LENGTH_LONG).show()
                    }
                }
            }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) {
                Text(if (reposted) "↻  Reposted" else "↻  Repost", color = if (reposted) EzoneRed else EzoneWhite, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val shareText = buildString {
                    append(post.body)
                    if (!post.mediaPath.isNullOrBlank()) {
                        if (isNotBlank()) append("\\n")
                        append(EzoneBackend.mediaUrl(context, post.mediaPath))
                    }
                }.ifBlank { "Check out this post on Ezone" }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                try { context.startActivity(Intent.createChooser(send, "Share post")) }
                catch (_: Exception) { Toast.makeText(context, "Sharing is unavailable on this device.", Toast.LENGTH_SHORT).show() }
            }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) { Text("↗", color = EzoneWhite, fontSize = 18.sp) }
            TextButton(onClick = { reportOpen = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)) { Text("⋯", color = EzoneMuted, fontSize = 19.sp) }
        }
    }
    if (reportOpen) {
        AlertDialog(
            onDismissRequest = { reportOpen = false },
            containerColor = EzoneSurface,
            title = { Text("Report this post", color = EzoneWhite) },
            text = {
                Column {
                    listOf("spam", "harassment", "nudity", "violence", "scam", "other").forEach { reason ->
                        Row(Modifier.fillMaxWidth().clickable { reportReason = reason }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reportReason == reason, onClick = { reportReason = reason }, colors = RadioButtonDefaults.colors(selectedColor = EzoneRed))
                            Text(reason.replaceFirstChar { it.uppercase() }, color = EzoneWhite)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val payload = JSONObject().put("reporter_id", myId).put("post_id", post.id).put("reason", reportReason)
                    EzoneBackend.request(context, "POST", "/rest/v1/content_reports", EzoneSession.accessToken, payload, "return=minimal") { result ->
                        if (result.ok) {
                            reportOpen = false
                            Toast.makeText(context, "Report submitted.", Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(context, EzoneBackend.errorMessage(result, "Could not submit report."), Toast.LENGTH_LONG).show()
                    }
                }) { Text("Submit report", color = EzoneRed) }
            },
            dismissButton = { TextButton(onClick = { reportOpen = false }) { Text("Cancel", color = EzoneMuted) } }
        )
    }
}

private fun likePost(context: Context, token: String, userId: String, post: EzonePost, done: () -> Unit) {
    val liked = userId in post.likedBy
    val path = if (liked) {
        "/rest/v1/post_likes?post_id=eq." + post.id + "&user_id=eq." + userId
    } else "/rest/v1/post_likes"
    val body = if (liked) null else JSONObject().put("post_id", post.id).put("user_id", userId)
    EzoneBackend.request(context, if (liked) "DELETE" else "POST", path, token, body, if (body == null) null else "return=minimal") { result ->
        if (result.ok) done()
        else Toast.makeText(context, EzoneBackend.errorMessage(result, "Could not update your like."), Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun CommentsDialog(
    context: Context,
    post: EzonePost,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var comments by remember(post.id) { mutableStateOf<List<EzoneComment>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    val token = EzoneSession.accessToken
    LaunchedEffect(post.id) {
        EzoneBackend.request(
            context, "GET",
            "/rest/v1/comments?select=id,user_id,body,created_at&post_id=eq." + post.id + "&order=created_at.asc&limit=100",
            token
        ) { result ->
            loading = false
            if (result.ok) comments = EzoneBackend.parseComments(result.body)
            else message = EzoneBackend.errorMessage(result, "Could not load comments.")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EzoneSurface,
        title = { Text("Comments", color = EzoneWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (loading) Text("Loading…", color = EzoneMuted)
                if (comments.isEmpty() && !loading) Text("No comments yet. Start the conversation.", color = EzoneMuted, fontSize = 12.sp)
                Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                    comments.forEach { comment ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(if (comment.userId == EzoneSession.userId) "You" else "Ezone member", color = EzoneRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(comment.body, color = EzoneWhite, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = EzoneLine)
                    }
                }
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), placeholder = { Text("Write a comment…", color = EzoneMuted) }, colors = darkFieldColors())
                if (message.isNotBlank()) Text(message, color = Color(0xFFBDBDBD), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = draft.trim().isNotEmpty(), onClick = {
                val body = JSONObject().put("post_id", post.id).put("user_id", EzoneSession.userId).put("body", draft.trim())
                EzoneBackend.request(context, "POST", "/rest/v1/comments", token, body, "return=minimal") { result ->
                    if (result.ok) {
                        comments = comments + EzoneComment("local-" + System.currentTimeMillis(), EzoneSession.userId, draft.trim(), "")
                        draft = ""
                        onChanged()
                    } else message = EzoneBackend.errorMessage(result, "Could not publish your comment.")
                }
            }) { Text("Send", color = EzoneRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = EzoneMuted) } }
    )
}

@Composable
private fun ProfileHeader(
    profile: EzoneProfile,
    isOwn: Boolean,
    isFollowing: Boolean,
    onEdit: () -> Unit,
    onMessage: () -> Unit,
    onFollow: () -> Unit,
    onSignOut: () -> Unit,
    onInsights: () -> Unit = {},
    onBlock: () -> Unit = {},
    onReport: () -> Unit = {},
    isFollowPending: Boolean = false,
    postCount: Int = 0,
    followerCount: Int = 0,
    followingCount: Int = 0
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accentColor = Color.White
    Column(Modifier.fillMaxWidth().background(EzoneBlack).padding(horizontal = 17.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(82.dp).clip(CircleShape).background(EzoneSurface2)) {
                Avatar(profile.displayName.take(1).uppercase(), 82, profile.avatarUrl)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.displayName, color = EzoneWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("@${profile.username}", color = EzoneMuted, fontSize = 12.sp)
                if (profile.pronouns.isNotBlank()) Text(profile.pronouns, color = EzoneMuted, fontSize = 10.sp)
            }
        }
        if (profile.bio.isNotBlank()) Text(profile.bio, color = EzoneWhite, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (profile.location.isNotBlank()) {
                Text("⌖  ${profile.location}", color = EzoneMuted, fontSize = 11.sp)
                if (profile.website.isNotBlank()) Spacer(Modifier.width(12.dp))
            }
            if (profile.website.isNotBlank()) {
                Text("↗  ${profile.website}", color = EzoneWhite, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.clickable {
                        val link = if (profile.website.startsWith("http://") || profile.website.startsWith("https://")) profile.website else "https://" + profile.website
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                        catch (_: Exception) { Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show() }
                    })
            }
        }
        Spacer(Modifier.height(17.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProfileStat(postCount.toString(), "Posts")
            ProfileStat(followerCount.toString(), "Followers")
            ProfileStat(followingCount.toString(), "Following")
        }
        Spacer(Modifier.height(14.dp))
        Spacer(Modifier.height(15.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isOwn) {
                Button(onClick = onEdit, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EzoneSurface2, contentColor = EzoneWhite)) {
                    Text("Edit profile", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onInsights, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, EzoneLine)) { Text("Share profile", color = EzoneWhite, fontSize = 12.sp) }
            } else {
                Button(onClick = onFollow, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isFollowing) EzoneSurface2 else accentColor)) {
                    Text(if (isFollowing) "Following" else if (isFollowPending) "Requested" else "Follow", color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onMessage, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, EzoneLine)) { Text("Message", color = EzoneWhite, fontSize = 12.sp) }
            }
        }
        if (!isOwn) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBlock, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Block", color = EzoneMuted, fontSize = 10.sp) }
                TextButton(onClick = onReport, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Report", color = EzoneRed, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: EzoneProfile, following: Boolean, isPrivate: Boolean, onOpen: () -> Unit, onFollow: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(profile.displayName.take(1).uppercase(), 42)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, color = EzoneWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("@" + profile.username, color = EzoneMuted, fontSize = 11.sp)
            if (profile.bio.isNotBlank()) Text(profile.bio, color = EzoneMuted, fontSize = 11.sp, maxLines = 1)
        }
        TextButton(onClick = onFollow) { Text(if (following) "Following" else if (isPrivate) "Request" else "Follow", color = if (following) EzoneMuted else EzoneRed, fontSize = 12.sp) }
    }
}

@Composable
private fun ProfileStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = EzoneWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = EzoneMuted, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun ProfileGalleryTabs(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(EzoneBlack).padding(horizontal = 10.dp, vertical = 5.dp)) {
        listOf("Posts" to "Posts", "Reels" to "Reels", "Tagged" to "Tagged").forEach { (value, label) ->
            val active = selected == value
            Column(
                Modifier.weight(1f).clickable { onSelect(value) }.padding(vertical = 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(label, color = if (active) EzoneWhite else EzoneMuted, fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(if (active) EzoneWhite else Color.Transparent))
            }
        }
    }
}

@Composable
private fun ProfilePostTile(context: Context, post: EzonePost, modifier: Modifier = Modifier, pinned: Boolean = false, canPin: Boolean = false, onPin: () -> Unit = {}) {
    val media = post.mediaPath
    Box(modifier.aspectRatio(0.82f).clip(RoundedCornerShape(0.dp)).background(EzoneSurface2).clickable {
        if (!media.isNullOrBlank()) {
            val url = EzoneBackend.mediaUrl(context, media)
            val mime = if (post.mediaType == "video") "video/*" else "image/*"
            try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)) }
            catch (_: Exception) { Toast.makeText(context, "No compatible viewer found.", Toast.LENGTH_SHORT).show() }
        }
    }) {
        if (!media.isNullOrBlank() && post.mediaType == "image") {
            AsyncImage(model = EzoneBackend.mediaUrl(context, media), contentDescription = "Profile post", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (post.mediaType == "video") "▶" else "✦", color = EzoneRed, fontSize = 26.sp)
                Text(if (post.mediaType == "video") "VIDEO" else post.body.take(28).ifBlank { "POST" }, color = EzoneWhite, fontSize = 9.sp, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
        if (post.mediaType == "video") Text("▶", Modifier.align(Alignment.TopEnd).padding(6.dp), color = Color.White, fontSize = 12.sp)
        if (canPin) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clickable(onClick = onPin),
                color = Color(0xCC111111), shape = CircleShape
            ) {
                Text(if (pinned) "📌" else "＋", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun EditProfileDialog(profile: EzoneProfile, context: Context, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.displayName) }
    var username by remember(profile.id) { mutableStateOf(profile.username) }
    var bio by remember(profile.id) { mutableStateOf(profile.bio) }
    var website by remember(profile.id) { mutableStateOf(profile.website) }
    var location by remember(profile.id) { mutableStateOf(profile.location) }
    var pronouns by remember(profile.id) { mutableStateOf(profile.pronouns) }
    var accent by remember(profile.id) { mutableStateOf(profile.accentColor.ifBlank { "#FFFFFF" }) }
    var avatarUri by remember(profile.id) { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> avatarUri = uri }
    AlertDialog(onDismissRequest = onDismiss, containerColor = EzoneSurface,
        title = { Text("Customize your profile", color = EzoneWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (avatarUri != null) AsyncImage(model = avatarUri, contentDescription = "New profile photo", modifier = Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else Avatar(name.take(1).uppercase(), 64, profile.avatarUrl)
                    Spacer(Modifier.width(12.dp))
                    Column { Text("Profile photo", color = EzoneWhite, fontWeight = FontWeight.SemiBold); TextButton(onClick = { picker.launch("image/*") }) { Text("Choose photo", color = EzoneRed) } }
                }
                AuthField(name, { name = it }, "Display name")
                AuthField(username, { username = it }, "Username")
                AuthField(bio, { bio = it }, "Bio")
                AuthField(pronouns, { pronouns = it }, "Pronouns")
                AuthField(location, { location = it }, "Location")
                AuthField(website, { website = it }, "Website or link")
                Spacer(Modifier.height(8.dp))
                Text("Profile accent", color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("#FFFFFF", "#777777", "#BBBBBB", "#FFFFFF", "#777777").forEach { colorHex ->
                        val swatch = try { Color(android.graphics.Color.parseColor(colorHex)) } catch (_: Exception) { EzoneRed }
                        Box(Modifier.size(32.dp).clip(CircleShape).background(swatch).clickable { accent = colorHex }, contentAlignment = Alignment.Center) { if (accent == colorHex) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
                Text("Your photo, name, username and profile details are visible to other Ezone members.", color = EzoneMuted, fontSize = 10.sp)
                if (message.isNotBlank()) Text(message, color = Color(0xFFBDBDBD), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val cleanUsername = username.trim().removePrefix("@").lowercase()
                if (name.isBlank() || !cleanUsername.matches(Regex("[a-z0-9_]{3,24}"))) message = "Enter a name and a valid 3–24 character username."
                else {
                    busy = true
                    fun saveProfile(photoUrl: String?) {
                        val body = JSONObject().put("display_name", name.trim()).put("username", cleanUsername).put("bio", bio.trim())
                            .put("website", website.trim()).put("location", location.trim()).put("pronouns", pronouns.trim()).put("accent_color", accent)
                        if (photoUrl != null) body.put("avatar_url", photoUrl)
                        EzoneBackend.request(context, "PATCH", "/rest/v1/profiles?id=eq." + EzoneSession.userId, EzoneSession.accessToken, body, "return=minimal") { result ->
                            busy = false
                            if (result.ok) onSaved() else message = EzoneBackend.errorMessage(result, "Could not save your profile. The username may already be taken.")
                        }
                    }
                    val selected = avatarUri
                    if (selected == null) saveProfile(null) else {
                        val mime = context.contentResolver.getType(selected).orEmpty()
                        val ext = if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg"
                        val path = EzoneSession.userId + "/avatar_" + System.currentTimeMillis() + "." + ext
                        EzoneBackend.upload(context, selected, path, EzoneSession.accessToken) { result ->
                            if (result.ok) saveProfile(EzoneBackend.mediaUrl(context, path))
                            else { busy = false; message = EzoneBackend.errorMessage(result, "Profile photo upload failed.") }
                        }
                    }
                }
            }) { Text(if (busy) "Saving…" else "Save changes", color = EzoneRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = EzoneMuted) } }
    )
}
@Composable
private fun ChangePasswordDialog(context: Context, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EzoneSurface,
        title = { Text("Set a new password", color = EzoneWhite) },
        text = {
            Column {
                AuthField(password, { password = it }, "New password", secret = true)
                AuthField(confirm, { confirm = it }, "Confirm password", secret = true)
                if (message.isNotBlank()) Text(message, color = Color(0xFFBDBDBD), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (password.length < 6) message = "Use at least 6 characters."
                else if (password != confirm) message = "Passwords do not match."
                else {
                    busy = true
                    EzoneBackend.request(
                        context, "PUT", "/auth/v1/user",
                        EzoneSession.accessToken, JSONObject().put("password", password)
                    ) { result ->
                        busy = false
                        if (result.ok) {
                            Toast.makeText(context, "Password updated.", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else message = EzoneBackend.errorMessage(result, "Could not update password.")
                    }
                }
            }) { Text(if (busy) "Saving…" else "Update password", color = EzoneRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later", color = EzoneMuted) } }
    )
}

@Composable
private fun Avatar(letter: String, size: Int, imageUrl: String? = null) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF303030), Color(0xFF202020)))), contentAlignment = Alignment.Center) {
        if (!imageUrl.isNullOrBlank()) AsyncImage(model = imageUrl, contentDescription = "Profile photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(letter.ifBlank { "E" }, color = EzoneRed, fontSize = (size / 2.2f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = EzoneWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = EzoneMuted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LoadingLine(message: String) {
    Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = EzoneRed, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(message, color = EzoneMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(message, color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EzoneWhite,
    unfocusedTextColor = EzoneWhite,
    focusedBorderColor = EzoneRed,
    unfocusedBorderColor = Color(0xFF454545),
    focusedContainerColor = EzoneSurface,
    unfocusedContainerColor = EzoneSurface,
    cursorColor = EzoneRed
)
