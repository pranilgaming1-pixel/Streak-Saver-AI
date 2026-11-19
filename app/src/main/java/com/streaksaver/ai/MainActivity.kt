package com.streaksaver.ai

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
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
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject

private val EzoneBlack = Color(0xFF050607)
private val EzoneSurface = Color(0xFF101316)
private val EzoneSurface2 = Color(0xFF171C20)
private val EzoneWhite = Color(0xFFF6F8FA)
private val EzoneMuted = Color(0xFF9AA5AD)
private val EzoneBlue = Color(0xFF8EDBFF)
private val EzoneLine = Color(0xFF29333A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(5, 6, 7)
        window.navigationBarColor = AndroidColor.rgb(5, 6, 7)
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
        primary = EzoneBlue,
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
            Brush.verticalGradient(listOf(Color(0xFF102630), Color(0xFF090E12), EzoneBlack))
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
            Text("A place for your people.", color = Color(0xFFB9E6F6), fontSize = 14.sp)
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
                    border = BorderStroke(1.dp, Color(0xFF536B78))
                ) {
                    Text("G", color = EzoneBlue, fontWeight = FontWeight.Black, fontSize = 18.sp)
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
            Text("BLACK  ·  WHITE  ·  ICE BLUE", color = Color(0xFF7FBDD6), fontSize = 10.sp, letterSpacing = 2.sp)
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
            focusedBorderColor = EzoneBlue, unfocusedBorderColor = Color(0xFF38434A),
            focusedContainerColor = EzoneSurface, unfocusedContainerColor = EzoneSurface,
            cursorColor = EzoneBlue
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
            containerColor = EzoneBlue,
            contentColor = Color(0xFF061018),
            disabledContainerColor = Color(0xFF35596A)
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
    var loading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf("") }
    var activeCommentsPost by remember { mutableStateOf<EzonePost?>(null) }
    var editProfileOpen by remember { mutableStateOf(false) }
    var recoveryDialogOpen by remember { mutableStateOf(EzoneSession.recoveryMode) }
    LaunchedEffect(EzoneSession.recoveryMode) { if (EzoneSession.recoveryMode) recoveryDialogOpen = true }
    val token = EzoneSession.accessToken
    val myId = EzoneSession.userId

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
                                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,created_at,profiles(id,username,display_name,bio,avatar_url),post_likes(user_id)&user_id=in.(" + inFilter + ")&order=created_at.desc&limit=50",
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
                        "/rest/v1/posts?select=id,user_id,body,media_path,media_type,created_at,profiles(id,username,display_name,bio,avatar_url),post_likes(user_id)&order=created_at.desc&limit=50",
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
                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,created_at,profiles(id,username,display_name,bio,avatar_url),post_likes(user_id)&media_path=not.is.null&order=created_at.desc&limit=60",
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
                    "/rest/v1/posts?select=id,user_id,body,media_path,media_type,created_at,profiles(id,username,display_name,bio,avatar_url),post_likes(user_id)&media_type=eq.video&order=created_at.desc&limit=60",
                    token
                ) { result ->
                    loading = false
                    if (result.ok) reelPosts = EzoneBackend.parsePosts(result.body)
                    else pageError = EzoneBackend.errorMessage(result, "Could not load Reels.")
                }
            }
            "Profile" -> {
                loading = true
                EzoneBackend.request(
                    context, "GET",
                    "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url&id=eq." + targetProfileId + "&limit=1",
                    token
                ) { result ->
                    if (result.ok) profile = EzoneBackend.parseProfiles(result.body).firstOrNull()
                    else pageError = EzoneBackend.errorMessage(result, "Could not load this profile.")
                    EzoneBackend.request(
                        context, "GET",
                        "/rest/v1/posts?select=id,user_id,body,media_path,media_type,created_at,profiles(id,username,display_name,bio,avatar_url),post_likes(user_id)&user_id=eq." + targetProfileId + "&order=created_at.desc&limit=50",
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
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ezone", color = EzoneWhite, fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.7).sp)
                Text("YOUR WORLD, YOUR PEOPLE", color = EzoneMuted, fontSize = 8.sp, letterSpacing = 1.8.sp)
            }
            TextButton(onClick = { messagesOpen = true }) { Text("✉", color = EzoneBlue, fontSize = 23.sp) }
            Box(Modifier.size(7.dp).background(EzoneBlue, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("BETA", color = EzoneMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        }
        HorizontalDivider(color = EzoneLine)
        if (sessionNotice.isNotBlank()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF31251B)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sessionNotice, color = Color(0xFFFFD9B0), fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissNotice) { Text("OK", color = EzoneBlue) }
            }
        }
        when (tab) {
            "Home" -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    listOf("For you", "Following").forEach { item ->
                        TextButton(onClick = { feedMode = item }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(item, color = if (feedMode == item) EzoneWhite else EzoneMuted, fontWeight = if (feedMode == item) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.height(3.dp))
                                if (feedMode == item) Box(Modifier.width(42.dp).height(2.dp).background(EzoneBlue, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }
                HorizontalDivider(color = EzoneLine)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        PostComposer(context, onPosted = { reloadKey++ })
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
            "Explore" -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Explore", color = EzoneWhite, fontSize = 25.sp, fontWeight = FontWeight.Black)
                            Text("Discover photos and videos shared by Ezone members.", color = EzoneMuted, fontSize = 12.sp)
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
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Reels", color = EzoneWhite, fontSize = 25.sp, fontWeight = FontWeight.Black)
                            Text("Short videos from the Ezone community.", color = EzoneMuted, fontSize = 12.sp)
                        }
                    }
                    if (loading && reelPosts.isEmpty()) item { LoadingLine("Loading Reels…") }
                    if (reelPosts.isEmpty() && !loading) item { EmptyState("No Reels yet", "Video posts shared by real members will appear here.") }
                    items(reelPosts, key = { it.id }) { post ->
                        PostCard(context, post, myId,
                            onLike = { likePost(context, token, myId, post) { reloadKey++ } },
                            onComments = { activeCommentsPost = post },
                            onProfile = { id -> targetProfileId = id; tab = "Profile" })
                        HorizontalDivider(color = EzoneLine)
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
                            placeholder = { Text("Search people", color = EzoneMuted) },
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
                                    "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url&or=(username.ilike.*" + encoded + "*,display_name.ilike.*" + encoded + "*)&limit=40",
                                    token
                                ) { result ->
                                    loading = false
                                    if (result.ok) searchResults = EzoneBackend.parseProfiles(result.body)
                                    else pageError = EzoneBackend.errorMessage(result, "Search failed.")
                                }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = EzoneBlue, contentColor = Color(0xFF061018))) {
                            Text("Search", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (loading) LoadingLine("Searching…")
                    if (searchResults.isEmpty() && searchText.isNotBlank() && !loading) {
                        EmptyState("No people found", "Try another username or display name.")
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
                            Box(Modifier.size(38.dp).background(Color(0xFF1A3542), CircleShape), contentAlignment = Alignment.Center) {
                                Text(notification.type.take(1).uppercase(), color = EzoneBlue, fontWeight = FontWeight.Bold)
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
                                onSignOut = { EzoneSession.clear(context); EzoneSession.setRecoveryMode(context, false) }
                            )
                            HorizontalDivider(color = EzoneLine)
                        } else {
                            EmptyState("Profile unavailable", "This account may not have finished setting up its profile.")
                        }
                    }
                    items(profilePosts, key = { it.id }) { post ->
                        PostCard(
                            context, post, myId,
                            onLike = { likePost(context, token, myId, post) { reloadKey++ } },
                            onComments = { activeCommentsPost = post },
                            onProfile = { id -> targetProfileId = id; tab = "Profile" }
                        )
                        HorizontalDivider(color = EzoneLine)
                    }
                    if (pageError.isNotBlank()) item { ErrorLine(pageError) }
                }
            }
        }
        HorizontalDivider(color = EzoneLine)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Home", "Search", "Explore", "Reels", "Create", "Activity", "Profile").forEach { item ->
                val selected = tab == item || (item == "Create" && tab == "Home")
                TextButton(onClick = {
                    if (item == "Create") tab = "Home" else tab = item
                    if (item == "Profile") targetProfileId = myId
                }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (item) {
                                "Home" -> "⌂"
                                "Search" -> "⌕"
                                "Explore" -> "▦"
                                "Reels" -> "▶"
                                "Create" -> "+"
                                "Activity" -> "♡"
                                else -> "◉"
                            },
                            color = if (selected) EzoneBlue else EzoneMuted,
                            fontSize = if (item == "Create") 25.sp else 21.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(item, color = if (selected) EzoneBlue else EzoneMuted, fontSize = 9.sp)
                    }
                }
            }
        }
    }

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
private fun PostComposer(context: Context, onPosted: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    var attachmentType by remember { mutableStateOf("image") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val token = EzoneSession.accessToken
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { attachment = uri; attachmentType = "image"; message = "" }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { attachment = uri; attachmentType = "video"; message = "" }
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
                        Text("▶ VIDEO", color = EzoneBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { attachment = null }) { Text("Remove", color = Color(0xFFFF9D9D), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = { photoPicker.launch("image/*") }) { Text("＋ Photo", color = EzoneBlue, fontSize = 12.sp) }
            TextButton(enabled = !busy, onClick = { videoPicker.launch("video/*") }) { Text("＋ Video", color = EzoneBlue, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy && (draft.trim().isNotEmpty() || attachment != null),
                onClick = {
                    busy = true
                    message = ""
                    fun createPost(mediaPath: String?, mediaType: String?) {
                        val body = JSONObject()
                            .put("user_id", EzoneSession.userId)
                            .put("body", draft.trim())
                            .put("media_path", mediaPath ?: JSONObject.NULL)
                            .put("media_type", mediaType ?: JSONObject.NULL)
                        EzoneBackend.request(context, "POST", "/rest/v1/posts", token, body, "return=representation") { result ->
                            busy = false
                            if (result.ok) {
                                draft = ""
                                attachment = null
                                message = ""
                                onPosted()
                            } else {
                                message = EzoneBackend.errorMessage(result, "Your post could not be published.")
                            }
                        }
                    }
                    val selected = attachment
                    if (selected == null) {
                        createPost(null, null)
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
                            if (result.ok) createPost(remotePath, attachmentType)
                            else {
                                busy = false
                                message = EzoneBackend.errorMessage(result, "Media upload failed.")
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EzoneBlue, contentColor = Color(0xFF061018), disabledContainerColor = Color(0xFF293941)),
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(author?.displayName?.take(1)?.uppercase() ?: "E", 40)
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF14222A)).clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(mediaUrl), "video/*"))
                            } catch (_: Exception) {
                                Toast.makeText(context, "No video player found.", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▶", color = EzoneBlue, fontSize = 42.sp)
                        Text("Tap to play video", color = EzoneWhite, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLike, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text((if (liked) "♥  " else "♡  ") + post.likedBy.size.toString(), color = if (liked) EzoneBlue else EzoneMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onComments, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Comment", color = EzoneMuted, fontSize = 12.sp)
            }
        }
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
                            Text(if (comment.userId == EzoneSession.userId) "You" else "Ezone member", color = EzoneBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            }) { Text("Send", color = EzoneBlue) }
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
    onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.displayName.take(1).uppercase(), 62)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.displayName, color = EzoneWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("@" + profile.username, color = EzoneMuted, fontSize = 12.sp)
            }
            if (isOwn) {
                TextButton(onClick = onEdit) { Text("Edit", color = EzoneBlue) }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(onClick = onMessage, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
                        Text("Message", color = EzoneBlue, fontSize = 11.sp)
                    }
                    Button(onClick = onFollow, colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFollowing) EzoneSurface2 else EzoneBlue,
                    contentColor = if (isFollowing) EzoneWhite else Color(0xFF061018)
                )) {
                    Text(if (isFollowing) "Following" else "Follow", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (profile.bio.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(profile.bio, color = EzoneWhite, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ezone member", color = EzoneMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            if (isOwn) TextButton(onClick = onSignOut) { Text("Log out", color = Color(0xFFFF9D9D), fontSize = 12.sp) }
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
        TextButton(onClick = onFollow) { Text(if (following) "Following" else "Follow", color = if (following) EzoneMuted else EzoneBlue, fontSize = 12.sp) }
    }
}

@Composable
private fun EditProfileDialog(
    profile: EzoneProfile,
    context: Context,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var name by remember(profile.id) { mutableStateOf(profile.displayName) }
    var username by remember(profile.id) { mutableStateOf(profile.username) }
    var bio by remember(profile.id) { mutableStateOf(profile.bio) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EzoneSurface,
        title = { Text("Edit profile", color = EzoneWhite) },
        text = {
            Column {
                AuthField(name, { name = it }, "Display name")
                AuthField(username, { username = it }, "Username")
                AuthField(bio, { bio = it }, "Bio")
                if (message.isNotBlank()) Text(message, color = Color(0xFFFF9D9D), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val cleanUsername = username.trim().removePrefix("@").lowercase()
                if (name.isBlank() || !cleanUsername.matches(Regex("[a-z0-9_]{3,24}"))) {
                    message = "Enter a name and a valid 3–24 character username."
                } else {
                    busy = true
                    val body = JSONObject().put("display_name", name.trim()).put("username", cleanUsername).put("bio", bio.trim())
                    EzoneBackend.request(
                        context, "PATCH",
                        "/rest/v1/profiles?id=eq." + EzoneSession.userId,
                        EzoneSession.accessToken, body, "return=minimal"
                    ) { result ->
                        busy = false
                        if (result.ok) onSaved()
                        else message = EzoneBackend.errorMessage(result, "Could not save your profile. The username may already be taken.")
                    }
                }
            }) { Text(if (busy) "Saving…" else "Save", color = EzoneBlue) }
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
            }) { Text(if (busy) "Saving…" else "Update password", color = EzoneBlue) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later", color = EzoneMuted) } }
    )
}

@Composable
private fun Avatar(letter: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(
            Brush.linearGradient(listOf(Color(0xFF234B5C), Color(0xFF11232B)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(letter.ifBlank { "E" }, color = EzoneBlue, fontSize = (size / 2.2f).sp, fontWeight = FontWeight.Bold)
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
        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = EzoneBlue, strokeWidth = 2.dp)
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
    focusedBorderColor = EzoneBlue,
    unfocusedBorderColor = Color(0xFF38434A),
    focusedContainerColor = EzoneSurface,
    unfocusedContainerColor = EzoneSurface,
    cursorColor = EzoneBlue
)
