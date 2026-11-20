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

private val EzoneBlack = Color(0xFF050505)
private val EzoneSurface = Color(0xFF111111)
private val EzoneSurface2 = Color(0xFF1B1B1B)
private val EzoneWhite = Color(0xFFFFFFFF)
private val EzoneMuted = Color(0xFFAAAAAA)
private val EzoneRed = Color(0xFFED233C)
private val EzoneLine = Color(0xFF292929)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(5, 5, 5)
        window.navigationBarColor = AndroidColor.rgb(17, 16, 29)
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
        onPrimary = Color(0xFF061018),
        background = EzoneBlack,
        surface = EzoneSurface,
        onSurface = EzoneWhite
    )) {
        Surface(Modifier.fillMaxSize(), color = EzoneBlack) {
            if (EzoneSession.accessToken.isBlank()) {
                AuthScreen(context)
            } else {
                LaunchedEffect(EzoneSession.accessToken, EzoneSession.expiresAt) {
                    if (EzoneSession.isExpiringSoon()) {
                        EzoneBackend.refreshSession(context) { ok, message ->
                            sessionNotice = if (ok) "" else message
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
            Brush.verticalGradient(listOf(Color(0xFF26070B), Color(0xFF090909), EzoneBlack))
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
            Text("A place for your people.", color = Color(0xFFFF8997), fontSize = 14.sp)
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
                    Text(message, color = if (message.contains("created", true) || message.contains("confirm", true)) Color(0xFF9DE4B8) else Color(0xFFFFA0A0), fontSize = 12.sp, textAlign = TextAlign.Center)
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
                    border = BorderStroke(1.dp, Color(0xFF6B252E))
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
            focusedBorderColor = EzoneRed, unfocusedBorderColor = Color(0xFF553039),
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
            disabledContainerColor = Color(0xFF6B252E)
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
    var tab by remember { mutableStateOf("Home") }
    var feedMode by remember { mutableStateOf("For you") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var targetProfileId by remember { mutableStateOf(EzoneSession.userId) }
    var messagesOpen by remember { mutableStateOf(false) }
    var chatUserId by remember { mutableStateOf("") }
    var chatUserName by remember { mutableStateOf("Ezone member") }
    var profile by remember { mutableStateOf<EzoneProfile?>(null) }
    var profilePosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var feedPosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var explorePosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var reelPosts by remember { mutableStateOf<List<EzonePost>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<EzoneProfile>>(emptyList()) }
    var followingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var notifications by remember { mutableStateOf<List<EzoneNotification>>(emptyList()) }
    var isFollowingTarget by remember { mutableStateOf(false) }
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

    LaunchedEffect(token, myId) {
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
                                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color),post_likes(user_id)&user_id=in.(" + inFilter + ")&order=created_at.desc&limit=50",
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
                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,music_path,music_title,music_artist,created_at,profiles:profiles!posts_user_id_profiles_fkey(id,username,display_name,bio,avatar_url),post_likes(user_id)&media_path=not.is.null&order=created_at.desc&limit=60",
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
                EzoneBackend.request(
                    context, "GET",
                    "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color&id=eq." + targetProfileId + "&limit=1",
                    token
                ) { result ->
                    if (result.ok) {
                        profile = EzoneBackend.parseProfiles(result.body).firstOrNull()
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
                if (targetProfileId != myId) {
                    EzoneBackend.request(
                        context, "GET",
                        "/rest/v1/follows?select=id&follower_id=eq." + myId + "&following_id=eq." + targetProfileId + "&limit=1",
                        token
                    ) { result ->
                        isFollowingTarget = result.ok && try { JSONArray(result.body).length() > 0 } catch (_: Exception) { false }
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
            Modifier.fillMaxWidth().height(58.dp).background(EzoneSurface).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = EzoneSurface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(36.dp).clickable { if (tab == "Home") tab = "Explore" else tab = "Home" }) {
                Box(contentAlignment = Alignment.Center) { Text("▦", color = EzoneWhite, fontSize = 19.sp) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(if (tab == "Profile" && targetProfileId == myId) "Profile" else if (tab == "Create") "New post" else "EZONE", color = EzoneWhite, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.2.sp)
            }
            Surface(color = EzoneSurface2, shape = CircleShape, modifier = Modifier.size(38.dp).clickable { targetProfileId = myId; tab = "Profile" }) {
                Box(contentAlignment = Alignment.Center) { Avatar(EzoneSession.email.take(1).uppercase(), 34) }
            }
        }
        HorizontalDivider(color = EzoneLine)
        if (sessionNotice.isNotBlank()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF31251B)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sessionNotice, color = Color(0xFFFFD9B0), fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissNotice) { Text("OK", color = EzoneRed) }
            }
        }
        when (tab) {
            "Home" -> {
                Row(Modifier.fillMaxWidth().background(EzoneSurface).padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                    listOf("For you", "Following").forEach { item ->
                        TextButton(onClick = { feedMode = item }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(item, color = if (feedMode == item) EzoneWhite else EzoneMuted, fontSize = 12.sp, fontWeight = if (feedMode == item) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.height(4.dp))
                                if (feedMode == item) Box(Modifier.width(48.dp).height(2.dp).background(EzoneRed, RoundedCornerShape(2.dp)))
                            }
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
                        item { EmptyState(
                            if (feedMode == "Following") "Your circle is quiet" else "Your Ezone starts here",
                            if (feedMode == "Following") "Follow people to see their posts here." else "Be the first to share something. Your feed contains real Ezone members, not demo profiles."
                        ) }
                    } else {
                        items(feedPosts, key = { it.id }) { post ->
                            PostCard(
                                context, post, myId,
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
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
                            Text("Create a post", color = EzoneWhite, fontSize = 26.sp, fontWeight = FontWeight.Black)
                            Text("Share a moment with your Ezone community.", color = EzoneMuted, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = EzoneLine)
                        PostComposer(context, onPosted = { tab = "Home"; reloadKey++ })
                    }
                }
            }
            "Explore" -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Explore", color = EzoneWhite, fontSize = 25.sp, fontWeight = FontWeight.Black)
                            Text("Discover photos and videos shared by Ezone members.", color = EzoneMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("For you", "Reels").forEach { filter ->
                                    Surface(color = if (filter == "For you") Color(0xFF211014) else EzoneSurface2, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { if (filter == "Reels") tab = "Reels" }) {
                                        Text(filter, color = if (filter == "For you") EzoneRed else EzoneMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (loading && explorePosts.isEmpty()) item { LoadingLine("Finding something new…") }
                    if (explorePosts.isEmpty() && !loading) item { EmptyState("Your Explore is waiting", "When members share photos and videos, they will appear here.") }
                    items(explorePosts, key = { it.id }) { post ->
                        PostCard(context, post, myId,
                            onLike = { likePost(context, token, myId, post) { reloadKey++ } },
                            onComments = { activeCommentsPost = post },
                            onProfile = { id -> targetProfileId = id; tab = "Profile" })
                        HorizontalDivider(color = EzoneLine)
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
                        ReelCard(context, post, myId, shouldPlay,
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
                            placeholder = { Text("Search by username", color = EzoneMuted) },
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
                                    "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url,website,location,pronouns,accent_color&or=(username.ilike.*" + encoded + "*,display_name.ilike.*" + encoded + "*)&limit=40",
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
                                ProfileRow(item, item.id in followingIds, {
                                    targetProfileId = item.id
                                    tab = "Profile"
                                }, {
                                    val isFollowing = item.id in followingIds
                                    val method = if (isFollowing) "DELETE" else "POST"
                                    val path = if (isFollowing) {
                                        "/rest/v1/follows?follower_id=eq." + myId + "&following_id=eq." + item.id
                                    } else "/rest/v1/follows"
                                    val body = if (isFollowing) null else JSONObject().put("follower_id", myId).put("following_id", item.id)
                                    EzoneBackend.request(context, method, path, token, body, if (body != null) "return=minimal" else null) { result ->
                                        if (result.ok) {
                                            followingIds = if (isFollowing) followingIds - item.id else followingIds + item.id
                                        } else pageError = EzoneBackend.errorMessage(result, "Could not update follow.")
                                    }
                                })
                                HorizontalDivider(color = EzoneLine)
                            }
                        }
                    }
                    if (pageError.isNotBlank()) ErrorLine(pageError)
                }
            }
            "Activity" -> {
                if (loading && notifications.isEmpty()) LoadingLine("Loading activity…")
                if (notifications.isEmpty() && !loading) EmptyState("Nothing new yet", "Likes, comments and new followers will appear here.")
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(notifications, key = { it.id }) { notification ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(Color(0xFF211014), CircleShape), contentAlignment = Alignment.Center) {
                                Text(notification.type.take(1).uppercase(), color = EzoneRed, fontWeight = FontWeight.Bold)
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
            "Profile" -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        if (loading && profile == null) LoadingLine("Loading profile…")
                        else if (profile != null) {
                            ProfileHeader(
                                profile = profile!!,
                                isOwn = targetProfileId == myId,
                                isFollowing = isFollowingTarget,
                                onEdit = { editProfileOpen = true },
                                onMessage = { messagesOpen = true; chatUserId = targetProfileId; chatUserName = profile!!.displayName },
                                onFollow = {
                                    val isFollowing = isFollowingTarget
                                    val method = if (isFollowing) "DELETE" else "POST"
                                    val path = if (isFollowing) {
                                        "/rest/v1/follows?follower_id=eq." + myId + "&following_id=eq." + targetProfileId
                                    } else "/rest/v1/follows"
                                    val body = if (isFollowing) null else JSONObject().put("follower_id", myId).put("following_id", targetProfileId)
                                    EzoneBackend.request(context, method, path, token, body, if (body != null) "return=minimal" else null) { result ->
                                        if (result.ok) isFollowingTarget = !isFollowing
                                        else pageError = EzoneBackend.errorMessage(result, "Could not update follow.")
                                    }
                                },
                                onSignOut = { EzoneSession.clear(context); EzoneSession.setRecoveryMode(context, false) },
                                postCount = profilePosts.size,
                                followerCount = profileFollowerCount,
                                followingCount = profileFollowingCount
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
                        ProfileGalleryTabs()
                    }
                    items(profilePosts.chunked(3), key = { row -> row.firstOrNull()?.id ?: "empty" }) { rowPosts ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowPosts.forEach { post ->
                                ProfilePostTile(context, post, Modifier.weight(1f))
                            }
                            repeat(3 - rowPosts.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (profilePosts.isEmpty() && !loading) item { EmptyState("No posts yet", "Your photos and videos will appear here.") }
                    if (pageError.isNotBlank()) item { ErrorLine(pageError) }
                }
            }
        }
        HorizontalDivider(color = EzoneLine)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF111111)).padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Home", "Explore", "Reels", "Create", "Activity", "Profile").forEach { item ->
                val selected = tab == item
                TextButton(onClick = {
                    tab = item
                    if (item == "Profile") targetProfileId = myId
                }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = if (selected || item == "Create") Color(0xFF42101A) else Color.Transparent, shape = RoundedCornerShape(13.dp)) {
                            Box(Modifier.size(width = if (item == "Create") 43.dp else 40.dp, height = 38.dp), contentAlignment = Alignment.Center) {
                                Text(when (item) { "Home" -> "⌂"; "Explore" -> "⌕"; "Reels" -> "▶"; "Create" -> "+"; "Activity" -> "♡"; else -> "◉" }, color = if (selected) EzoneWhite else EzoneMuted, fontSize = if (item == "Create") 26.sp else 21.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        
                    }
                }
            }
        }
    }

    if (selectedStory != null) StoryViewer(context, selectedStory!!, onDismiss = { selectedStory = null })
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
            Text(if (busy) "Uploading…" else "See all", color = EzoneMuted, fontSize = 10.sp)
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
                    Box(Modifier.size(58.dp).clip(CircleShape).background(Brush.linearGradient(listOf(EzoneRed, Color(0xFF8C1828)))).padding(3.dp)) {
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
    Column(Modifier.fillMaxWidth().background(EzoneBlack).padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(author?.displayName?.take(1)?.uppercase() ?: "E", 40, author?.avatarUrl)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(author?.displayName ?: "Ezone member", color = EzoneWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable { author?.id?.let(onProfile) })
                Text("@${author?.username ?: "member"}", color = EzoneMuted, fontSize = 10.sp)
            }
            Text("REEL", color = EzoneRed, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        }
        if (!post.mediaPath.isNullOrBlank()) {
            EzoneVideoPlayer(context, EzoneBackend.mediaUrl(context, post.mediaPath), Modifier.fillMaxWidth().height(570.dp), loop = true, showController = true, autoPlay = autoPlay)
        } else {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("This Reel has no video attached.", color = EzoneMuted) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLike) { Text((if (myId in post.likedBy) "♥" else "♡") + " " + post.likedBy.size, color = if (myId in post.likedBy) EzoneRed else EzoneWhite, fontSize = 18.sp) }
            TextButton(onClick = onComments) { Text("Comment", color = EzoneWhite, fontSize = 12.sp) }
        }
        if (post.body.isNotBlank()) Text(post.body, Modifier.padding(horizontal = 18.dp), color = EzoneWhite, fontSize = 13.sp)
        HorizontalDivider(color = EzoneLine, modifier = Modifier.padding(top = 12.dp))
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

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(EzoneSession.email.substringBefore("@").take(1).uppercase().ifBlank { "E" }, 42)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(EzoneSession.email.substringBefore("@").ifBlank { "Your profile" }, color = EzoneWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("What's happening?", color = EzoneMuted) },
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
                        modifier = Modifier.size(86.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.size(86.dp).clip(RoundedCornerShape(10.dp)).background(EzoneSurface2), contentAlignment = Alignment.Center) {
                        Text("▶ VIDEO", color = EzoneRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { attachment = null }) { Text("Remove", color = Color(0xFFFF9D9D), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = { photoPicker.launch("image/*") }) { Text("＋ Photo", color = EzoneRed, fontSize = 12.sp) }
            TextButton(enabled = !busy, onClick = { videoPicker.launch("video/*") }) { Text("＋ Video", color = EzoneRed, fontSize = 12.sp) }
            TextButton(enabled = !busy, onClick = { audioPicker.launch("audio/*") }) { Text("♫ Music", color = EzoneRed, fontSize = 12.sp) }
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
                colors = ButtonDefaults.buttonColors(containerColor = EzoneRed, contentColor = Color(0xFF061018), disabledContainerColor = Color(0xFF292020)),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 7.dp)
            ) {
                Text(if (busy) "Posting…" else "Post", fontWeight = FontWeight.Bold)
            }
        }
        if (message.isNotBlank()) Text(message, color = Color(0xFFFFA0A0), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PostCard(
    context: Context,
    post: EzonePost,
    myId: String,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onProfile: (String) -> Unit
) {
    val author = post.author
    val liked = myId in post.likedBy
    Column(Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 7.dp).background(EzoneSurface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.sweepGradient(listOf(EzoneRed, Color(0xFFFF5268), EzoneRed))).padding(2.dp)) {
                Avatar(author?.displayName?.take(1)?.uppercase() ?: "E", 38, author?.avatarUrl)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(author?.displayName ?: "Ezone member", color = EzoneWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { author?.id?.let(onProfile) })
                Text("@" + (author?.username ?: "member") + "  ·  " + post.createdAt.substringBefore("T"), color = EzoneMuted, fontSize = 10.sp,
                    modifier = Modifier.clickable { author?.id?.let(onProfile) })
            }
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
                    modifier = Modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(13.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                EzoneVideoPlayer(
                    context = context,
                    url = mediaUrl,
                    modifier = Modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(13.dp)),
                    loop = false,
                    showController = true
                )
            }
        }
        if (!post.musicPath.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().padding(top = 9.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B0B0E))
                .clickable {
                    val audioUrl = EzoneBackend.mediaUrl(context, post.musicPath)
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(audioUrl), "audio/*")) }
                    catch (_: Exception) { Toast.makeText(context, "No audio player found.", Toast.LENGTH_SHORT).show() }
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF1A4052)), contentAlignment = Alignment.Center) { Text("♫", color = EzoneRed, fontSize = 20.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(post.musicTitle ?: "Audio track", color = EzoneWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(post.musicArtist ?: "Ezone audio", color = EzoneMuted, fontSize = 10.sp) }
                Text("▶", color = EzoneRed, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLike, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) {
                Text((if (liked) "♥" else "♡") + "  " + post.likedBy.size, color = if (liked) Color(0xFFFF8DAA) else EzoneWhite, fontSize = 16.sp)
            }
            TextButton(onClick = onComments, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) { Text("▢", color = EzoneWhite, fontSize = 17.sp) }
            TextButton(onClick = onComments, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) { Text("♧", color = EzoneWhite, fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onComments, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) { Text("➤", color = EzoneWhite, fontSize = 17.sp) }
        }
        if (post.body.isNotBlank()) Text(post.body, color = EzoneWhite, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 8.dp, bottom = 5.dp))
    }
}

private fun likePost(context: Context, token: String, userId: String, post: EzonePost, done: () -> Unit) {
    val liked = userId in post.likedBy
    val path = if (liked) {
        "/rest/v1/post_likes?post_id=eq." + post.id + "&user_id=eq." + userId
    } else "/rest/v1/post_likes"
    val body = if (liked) null else JSONObject().put("post_id", post.id).put("user_id", userId)
    EzoneBackend.request(context, if (liked) "DELETE" else "POST", path, token, body, if (body == null) null else "return=minimal") {
        done()
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
                if (message.isNotBlank()) Text(message, color = Color(0xFFFFA0A0), fontSize = 11.sp)
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
    postCount: Int = 0,
    followerCount: Int = 0,
    followingCount: Int = 0
) {
    val accentColor = try { Color(android.graphics.Color.parseColor(profile.accentColor)) } catch (_: Exception) { EzoneRed }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)).background(EzoneSurface).padding(horizontal = 20.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Brush.sweepGradient(listOf(accentColor, Color(0xFFFF5268), accentColor))).padding(3.dp).clip(CircleShape).background(EzoneSurface2).padding(3.dp)) {
            Avatar(profile.displayName.take(1).uppercase(), 76, profile.avatarUrl)
        }
        Spacer(Modifier.height(9.dp))
        Text(profile.displayName, color = EzoneWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(if (profile.website.isNotBlank()) profile.website else "@${profile.username}", color = EzoneMuted, fontSize = 11.sp)
        if (profile.bio.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(profile.bio, color = EzoneWhite, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
        }
        if (profile.pronouns.isNotBlank() || profile.location.isNotBlank() || profile.website.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(listOf(profile.pronouns, profile.location, profile.website).filter { it.isNotBlank() }.joinToString("  ·  "), color = EzoneMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(17.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProfileStat(postCount.toString(), "POSTS")
            ProfileStat(followerCount.toString(), "FOLLOWERS")
            ProfileStat(followingCount.toString(), "FOLLOWING")
        }
        Spacer(Modifier.height(17.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isOwn) {
                Button(onClick = onEdit, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)) { Text("Edit profile", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onSignOut, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, EzoneLine)) { Text("Log out", color = EzoneMuted) }
            } else {
                Button(onClick = onFollow, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isFollowing) EzoneSurface2 else accentColor)) { Text(if (isFollowing) "Following" else "Follow", color = EzoneWhite) }
                OutlinedButton(onClick = onMessage, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, EzoneLine)) { Text("Message", color = EzoneWhite) }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: EzoneProfile, following: Boolean, onOpen: () -> Unit, onFollow: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(profile.displayName.take(1).uppercase(), 42)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, color = EzoneWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("@" + profile.username, color = EzoneMuted, fontSize = 11.sp)
            if (profile.bio.isNotBlank()) Text(profile.bio, color = EzoneMuted, fontSize = 11.sp, maxLines = 1)
        }
        TextButton(onClick = onFollow) { Text(if (following) "Following" else "Follow", color = if (following) EzoneMuted else EzoneRed, fontSize = 12.sp) }
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
private fun ProfileGalleryTabs() {
    var selected by remember { mutableStateOf("Photos") }
    Row(Modifier.fillMaxWidth().background(EzoneSurface).padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("Photos", "Videos", "About").forEach { label ->
            TextButton(onClick = { selected = label }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = if (selected == label) EzoneWhite else EzoneMuted, fontSize = 11.sp, fontWeight = if (selected == label) FontWeight.Bold else FontWeight.Normal)
                    Spacer(Modifier.height(3.dp))
                    if (selected == label) Box(Modifier.width(30.dp).height(2.dp).background(EzoneRed, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun ProfilePostTile(context: Context, post: EzonePost, modifier: Modifier = Modifier) {
    val media = post.mediaPath
    Box(modifier.aspectRatio(0.82f).clip(RoundedCornerShape(10.dp)).background(EzoneSurface2).clickable {
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
    var accent by remember(profile.id) { mutableStateOf(profile.accentColor.ifBlank { "#ED233C" }) }
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
                    listOf("#ED233C", "#B51F32", "#7A1725", "#FFFFFF", "#777777").forEach { colorHex ->
                        val swatch = try { Color(android.graphics.Color.parseColor(colorHex)) } catch (_: Exception) { EzoneRed }
                        Box(Modifier.size(32.dp).clip(CircleShape).background(swatch).clickable { accent = colorHex }, contentAlignment = Alignment.Center) { if (accent == colorHex) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
                Text("Your photo, name, username and profile details are visible to other Ezone members.", color = EzoneMuted, fontSize = 10.sp)
                if (message.isNotBlank()) Text(message, color = Color(0xFFFF9D9D), fontSize = 11.sp)
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
                if (message.isNotBlank()) Text(message, color = Color(0xFFFF9D9D), fontSize = 11.sp)
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
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF42101A), Color(0xFF1B0B0E)))), contentAlignment = Alignment.Center) {
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
    Text(message, color = Color(0xFFFFA0A0), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EzoneWhite,
    unfocusedTextColor = EzoneWhite,
    focusedBorderColor = EzoneRed,
    unfocusedBorderColor = Color(0xFF553039),
    focusedContainerColor = EzoneSurface,
    unfocusedContainerColor = EzoneSurface,
    cursorColor = EzoneRed
)
