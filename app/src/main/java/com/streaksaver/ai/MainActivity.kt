package com.streaksaver.ai

import android.content.Context
import android.os.Bundle
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private val bg = Color(0xFF080B14)
private val panel = Color(0xFF141B2B)
private val violet = Color(0xFF9B7BFF)
private val cyan = Color(0xFF5DE1E8)
private val muted = Color(0xFF9BA8BF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8,11,20)
        window.navigationBarColor = android.graphics.Color.rgb(8,11,20)
        setContent { EzoneApp(this) }
    }
}

private object Supabase {
    private val io = Executors.newSingleThreadExecutor()
    fun post(context: Context, path: String, body: JSONObject, done: (Boolean, String, String, String) -> Unit) {
        val p = context.getSharedPreferences("ezone", Context.MODE_PRIVATE)
        val base = p.getString("url", "").orEmpty().trim().trimEnd('/')
        val key = p.getString("key", "").orEmpty().trim()
        if (base.isBlank() || key.isBlank()) { done(false, "Set your Supabase URL and anon/public key first.", "", ""); return }
        io.execute {
            var c: HttpURLConnection? = null
            val result = try {
                c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", key)
                }
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                if (code in 200..299) {
                    val token = json.optString("access_token")
                    val mail = json.optJSONObject("user")?.optString("email").orEmpty().ifBlank { body.optString("email") }
                    Triple(true, if (token.isBlank()) "Request accepted. Check your email if confirmation is enabled." else "Signed in successfully.", token + "|" + mail)
                } else {
                    val err = json.optString("msg").ifBlank { json.optString("message").ifBlank { json.optString("error_description").ifBlank { "Supabase returned HTTP " + code } } }
                    Triple(false, err, "")
                }
            } catch (e: Exception) { Triple(false, e.message ?: "Connection failed.", "") }
            finally { c?.disconnect() }
            Handler(context.mainLooper).post { done(result.first, result.second, result.third.substringBefore("|"), result.third.substringAfter("|", "")) }
        }
    }
}

@Composable private fun EzoneApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("ezone", Context.MODE_PRIVATE) }
    var url by remember { mutableStateOf(prefs.getString("url", "https://mfitbsoomnzcvhjdiujl.supabase.co").orEmpty()) }
    var key by remember { mutableStateOf(prefs.getString("key", "sb_publishable_VDMBGM98M1bfhYOIvLoXrQ_BPD3-UvU").orEmpty()) }
    var email by remember { mutableStateOf(prefs.getString("email", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Sign in") }
    var setup by remember { mutableStateOf(url.isBlank() || key.isBlank()) }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(background = bg, surface = panel, primary = violet, secondary = cyan)) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1731), bg)))) {
                Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("E Z O N E", color = violet, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Your circle. Your world.", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("A social space for real people.", color = muted, fontSize = 13.sp)
                    Spacer(Modifier.height(26.dp))
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = panel)) {
                        Column(Modifier.padding(20.dp)) {
                            Text(if (setup) "Connect Supabase" else if (token.isNotBlank()) "Welcome to Ezone" else mode, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(14.dp))
                            if (setup) {
                                Text("Create a free Supabase project, then copy Project URL and anon/public key from Project Settings → API.", color = muted, fontSize = 12.sp)
                                Input(url, { url = it }, "Project URL", KeyboardType.Uri)
                                Input(key, { key = it }, "Anon / publishable key")
                                Button(onClick = {
                                    if (!url.startsWith("https://") || key.isBlank()) message = "Enter a valid project URL and anon/public key."
                                    else { prefs.edit().putString("url", url.trim().trimEnd('/')).putString("key", key.trim()).apply(); setup = false; message = "Supabase settings saved." }
                                }, Modifier.fillMaxWidth()) { Text("Save connection") }
                            } else if (token.isNotBlank()) {
                                Text("Signed in as", color = muted, fontSize = 12.sp)
                                Text(email, color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                Text("Authentication is connected. Next we will connect your profile, posts and media storage to the Ezone database.", color = muted, fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = { token = ""; prefs.edit().remove("token").apply() }, Modifier.fillMaxWidth()) { Text("Sign out") }
                            } else {
                                if (mode == "Create account") {
                                    Input(name, { name = it }, "Your name")
                                    Input(username, { username = it }, "Username")
                                }
                                Input(email, { email = it }, "Email address", KeyboardType.Email)
                                Input(password, { password = it }, "Password", secret = true)
                                if (message.isNotBlank()) { Text(message, color = if (message.contains("success", true) || message.contains("accepted", true)) cyan else Color(0xFFFFB0B0), fontSize = 12.sp); Spacer(Modifier.height(8.dp)) }
                                Button(onClick = {
                                    if (email.isBlank() || password.length < 6) message = "Enter an email and password with at least 6 characters."
                                    else if (mode == "Create account" && (name.isBlank() || username.isBlank())) message = "Enter your name and username."
                                    else {
                                        busy = true
                                        val body = JSONObject().put("email", email.trim()).put("password", password)
                                        val path = if (mode == "Sign in") "/auth/v1/token?grant_type=password" else {
                                            body.put("data", JSONObject().put("display_name", name.trim()).put("username", username.trim()))
                                            "/auth/v1/signup"
                                        }
                                        Supabase.post(context, path, body) { ok, msg, access, userEmail ->
                                            busy = false; message = msg
                                            if (ok && access.isNotBlank()) {
                                                token = access; email = userEmail
                                                prefs.edit().putString("token", access).putString("email", userEmail).apply()
                                            }
                                        }
                                    }
                                }, Modifier.fillMaxWidth()) { Text(if (busy) "Please wait…" else mode) }
                                TextButton(onClick = { mode = if (mode == "Sign in") "Create account" else "Sign in"; message = "" }, Modifier.fillMaxWidth()) {
                                    Text(if (mode == "Sign in") "Create an account" else "Already registered? Sign in", color = cyan)
                                }
                                if (mode == "Sign in") TextButton(onClick = {
                                    if (email.isBlank()) message = "Enter your email first."
                                    else { busy = true; Supabase.post(context, "/auth/v1/recover", JSONObject().put("email", email.trim())) { ok, msg, _, _ -> busy = false; message = if (ok) "If the account exists, a reset email has been requested." else msg } }
                                }, Modifier.fillMaxWidth()) { Text("Forgot password?", color = muted) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!setup) TextButton(onClick = { setup = true; message = "" }) { Text("Supabase connection settings", color = cyan) }
                    Text("Email + password only · No Google sign-in", color = muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable private fun Input(value: String, change: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth().padding(bottom = 10.dp), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}
