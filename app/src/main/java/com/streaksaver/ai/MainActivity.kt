package com.streaksaver.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private val ink = Color(0xFF050505)
private val card = Color(0xFF101010)
private val soft = Color(0xFF9B9B9B)
private val accent = Color(0xFF8EDBFF)
private val white = Color(0xFFF7F7F7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        handleOAuthCallback(intent)
        setContent { EzoneApp(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "ezone" || uri.host != "auth") return
        val params = uri.fragment.orEmpty().split("&").mapNotNull { part ->
            val pair = part.split("=", limit = 2)
            if (pair.size == 2) pair[0] to Uri.decode(pair[1]) else null
        }.toMap()
        val access = params["access_token"].orEmpty()
        if (access.isNotBlank()) getSharedPreferences("ezone", Context.MODE_PRIVATE).edit()
            .putString("token", access).putString("email", "Google account").apply()
    }
}

private object Supabase {
    private val io = Executors.newSingleThreadExecutor()

    fun request(context: Context, endpoint: String, body: JSONObject, callback: (Boolean, String, String, String) -> Unit) {
        val prefs = context.getSharedPreferences("ezone", Context.MODE_PRIVATE)
        val base = prefs.getString("url", "https://mfitbsoomnzcvhjdiujl.supabase.co").orEmpty().trim().trimEnd('/')
        val apiKey = prefs.getString("key", "sb_publishable_VDMBGM98M1bfhYOIvLoXrQ_BPD3-UvU").orEmpty().trim()
        if (base.isBlank() || apiKey.isBlank()) {
            callback(false, "Supabase connection is missing. Open Connection settings.", "", "")
            return
        }
        io.execute {
            var connection: HttpURLConnection? = null
            val result = try {
                connection = (URL(base + endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", apiKey)
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = try { JSONObject(response) } catch (_: Exception) { JSONObject() }
                if (status in 200..299) {
                    val accessToken = json.optString("access_token")
                    val user = json.optJSONObject("user")
                    val returnedEmail = user?.optString("email").orEmpty().ifBlank { body.optString("email") }
                    val message = if (accessToken.isNotBlank()) "Signed in successfully."
                        else "Account request accepted. Check your email to confirm your account, then sign in."
                    Triple(true, message, accessToken + "|" + returnedEmail)
                } else {
                    val error = json.optString("msg")
                        .ifBlank { json.optString("message") }
                        .ifBlank { json.optString("error_description") }
                        .ifBlank { json.optString("error") }
                        .ifBlank { "Supabase error (HTTP $status). Check Auth settings and try again." }
                    Triple(false, error, "")
                }
            } catch (e: Exception) {
                Triple(false, e.localizedMessage ?: "Network error. Check your internet connection.", "")
            } finally {
                connection?.disconnect()
            }
            val packed = result.third
            Handler(context.mainLooper).post {
                callback(result.first, result.second, packed.substringBefore("|"), packed.substringAfter("|", ""))
            }
        }
    }
}

@Composable
private fun EzoneApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("ezone", Context.MODE_PRIVATE) }
    var email by remember { mutableStateOf(prefs.getString("email", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("login") }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var projectUrl by remember { mutableStateOf(prefs.getString("url", "https://mfitbsoomnzcvhjdiujl.supabase.co").orEmpty()) }
    var publishableKey by remember { mutableStateOf(prefs.getString("key", "sb_publishable_VDMBGM98M1bfhYOIvLoXrQ_BPD3-UvU").orEmpty()) }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = ink, surface = card)) {
        Surface(Modifier.fillMaxSize(), color = ink) {
            if (token.isNotBlank()) {
                ThreadsHome(email = email, onSignOut = {
                    token = ""
                    prefs.edit().remove("token").apply()
                    message = ""
                })
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10212B), Color(0xFF070B0E), ink)))) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Spacer(Modifier.height(42.dp))
                        Text("ezone", color = white, fontSize = 43.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
                        Spacer(Modifier.height(5.dp))
                        Text("A place for your people.", color = Color(0xFFB5DFF0), fontSize = 14.sp)
                        Spacer(Modifier.height(34.dp))

                        if (settingsOpen) {
                            Text("Connect your project", color = white, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            AuthField(projectUrl, { projectUrl = it }, "Supabase Project URL", KeyboardType.Uri)
                            AuthField(publishableKey, { publishableKey = it }, "Publishable key")
                            PrimaryAction("Save settings", false) {
                                if (!projectUrl.startsWith("https://") || publishableKey.isBlank()) message = "Enter a valid Project URL and publishable key."
                                else {
                                    prefs.edit().putString("url", projectUrl.trim().trimEnd('/')).putString("key", publishableKey.trim()).apply()
                                    settingsOpen = false
                                    message = "Connection settings saved."
                                }
                            }
                        } else {
                            if (mode == "signup") {
                                Text("Create your account", color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Join Ezone and find your people.", color = soft, fontSize = 13.sp)
                                Spacer(Modifier.height(20.dp))
                                AuthField(fullName, { fullName = it }, "Full name")
                                AuthField(username, { username = it }, "Username")
                            } else {
                                Text("Welcome back", color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Log in to continue to Ezone.", color = soft, fontSize = 13.sp)
                                Spacer(Modifier.height(20.dp))
                            }
                            AuthField(email, { email = it }, "Email address", KeyboardType.Email)
                            AuthField(password, { password = it }, "Password", secret = true)
                            Spacer(Modifier.height(8.dp))
                            PrimaryAction(if (busy) "Please wait…" else if (mode == "signup") "Sign up" else "Log in", busy) {
                                val cleanEmail = email.trim()
                                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                                    message = "Enter a valid email address."
                                } else if (password.length < 6) {
                                    message = "Your password must contain at least 6 characters."
                                } else if (mode == "signup" && (fullName.isBlank() || username.isBlank())) {
                                    message = "Enter your full name and choose a username."
                                } else {
                                    busy = true
                                    message = ""
                                    val payload = JSONObject().put("email", cleanEmail).put("password", password)
                                    val endpoint = if (mode == "login") "/auth/v1/token?grant_type=password" else {
                                        payload.put("data", JSONObject()
                                            .put("display_name", fullName.trim())
                                            .put("username", username.trim().removePrefix("@").lowercase()))
                                        "/auth/v1/signup"
                                    }
                                    Supabase.request(context, endpoint, payload) { success, info, accessToken, returnedEmail ->
                                        busy = false
                                        message = info
                                        if (success && accessToken.isNotBlank()) {
                                            token = accessToken
                                            email = returnedEmail
                                            prefs.edit().putString("token", accessToken).putString("email", returnedEmail).apply()
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            if (message.isNotBlank()) {
                                Text(message, color = if (message.contains("success", true) || message.contains("accepted", true) || message.contains("confirm", true)) Color(0xFF8FE3B0) else Color(0xFFFF9696), fontSize = 12.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(10.dp))
                            }
                            if (mode == "login") {
                                TextButton(enabled = !busy, onClick = {
                                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) message = "Enter your email address first."
                                    else {
                                        busy = true
                                        Supabase.request(context, "/auth/v1/recover", JSONObject().put("email", email.trim())) { ok, info, _, _ ->
                                            busy = false
                                            message = if (ok) "If the account exists, a password reset email has been sent." else info
                                        }
                                    }
                                }) { Text("Forgot password?", color = soft, fontSize = 12.sp) }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                HorizontalDivider(Modifier.weight(1f), color = Color(0xFF303030))
                                Text("  OR  ", color = soft, fontSize = 10.sp)
                                HorizontalDivider(Modifier.weight(1f), color = Color(0xFF303030))
                            }
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(
                                onClick = {
                                    val callback = Uri.encode("ezone://auth")
                                    val authUrl = projectUrl.trim().trimEnd('/') + "/auth/v1/authorize?provider=google&redirect_to=" + callback
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))) }
                                    catch (_: Exception) { message = "Could not open browser for Google sign-in." }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = white),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF536B78))
                            ) {
                                Text("G", color = Color(0xFF8EDBFF), fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Spacer(Modifier.width(10.dp))
                                Text("Continue with Google", color = white, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0xFF303030), modifier = Modifier.padding(vertical = 18.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Text(if (mode == "login") "Don't have an account? " else "Already have an account? ", color = soft, fontSize = 13.sp)
                                TextButton(enabled = !busy, onClick = {
                                    mode = if (mode == "login") "signup" else "login"
                                    message = ""
                                }, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (mode == "login") "Sign up" else "Log in", color = white, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                            TextButton(onClick = { settingsOpen = true; message = "" }) {
                                Text("Connection settings", color = Color(0xFF777777), fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(30.dp))
                        Text("BLACK · WHITE · ICE BLUE", color = Color(0xFF7FBDD6), fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("By continuing, you agree to Ezone's Terms and Privacy Policy.", color = Color(0xFF666666), fontSize = 10.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(30.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthField(value: String, onValueChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        label = { Text(label, color = soft, fontSize = 12.sp) },
        singleLine = true,
        shape = RoundedCornerShape(9.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = white, unfocusedTextColor = white,
            focusedBorderColor = Color(0xFF777777), unfocusedBorderColor = Color(0xFF363636),
            focusedContainerColor = Color(0xFF111111), unfocusedContainerColor = Color(0xFF111111),
            cursorColor = white
        )
    )
}

@Composable
private fun PrimaryAction(label: String, disabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !disabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8EDBFF), contentColor = Color(0xFF061018), disabledContainerColor = Color(0xFF35596A))
    ) {
        Text(label, color = Color(0xFF061018), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ThreadsHome(email: String, onSignOut: () -> Unit) {
    var selectedTab by remember { mutableStateOf("Home") }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(ink)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ezone", color = white, fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, modifier = Modifier.weight(1f))
            Text("●", color = accent, fontSize = 12.sp)
            Spacer(Modifier.width(7.dp))
            TextButton(onClick = onSignOut) { Text("Log out", color = soft, fontSize = 12.sp) }
        }
        HorizontalDivider(color = Color(0xFF292929))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("For you", "Following").forEach { tab ->
                TextButton(onClick = { selectedTab = tab }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tab, color = if (selectedTab == tab) white else soft, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal)
                        Spacer(Modifier.height(3.dp))
                        if (selectedTab == tab) Box(Modifier.width(42.dp).height(2.dp).background(accent, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF292929))
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(42.dp).background(Color(0xFF1C3541), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                Text(email.firstOrNull()?.uppercase() ?: "E", color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(email.substringBefore("@").ifBlank { "Your profile" }, color = white, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("What's happening?", color = soft) },
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = white, unfocusedTextColor = white,
                        focusedBorderColor = Color(0xFF536B78), unfocusedBorderColor = Color(0xFF303030),
                        focusedContainerColor = ink, unfocusedContainerColor = ink
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("Share your thoughts", color = soft, fontSize = 11.sp)
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { }, enabled = false, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 5.dp), colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF263D49))) {
                        Text("Post", color = Color(0xFF9CBAC8))
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF292929))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
                Text("Your Ezone starts here", color = white, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your feed will show posts from real people you follow. No sample accounts or bot friends.",
                    color = soft, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }
        }
        HorizontalDivider(color = Color(0xFF292929))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Home", "Search", "Create", "Activity", "Profile").forEach { item ->
                TextButton(onClick = { selectedTab = item }) {
                    Text(item, color = if (selectedTab == item) accent else soft, fontSize = 11.sp, fontWeight = if (selectedTab == item) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
