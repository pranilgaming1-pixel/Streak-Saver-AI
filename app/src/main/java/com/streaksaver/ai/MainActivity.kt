package com.streaksaver.ai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val AppBg = Color(0xFF0B0D14)
private val CardBg = Color(0xFF171B28)
private val Accent = Color(0xFF9B8CFF)
private val Muted = Color(0xFF969DB2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StreakReminderWorker.schedule(this)
        setContent { StreakSaverApp() }
    }
}

@Composable
fun StreakSaverApp() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).friendDao() }
    val scope = rememberCoroutineScope()
    val friends by dao.observeFriends().collectAsState(initial = emptyList())
    val prefs = remember { context.getSharedPreferences("streak_saver_private", Context.MODE_PRIVATE) }

    var addOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var selectedFriend by remember { mutableStateOf<FriendEntity?>(null) }
    var generatedMessage by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("Casual and friendly") }
    var isGenerating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Background reminders are active") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status = if (granted) "Notifications enabled" else "Notifications are disabled in Android settings"
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = AppBg, surface = CardBg, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = AppBg) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("STREAK SAVER AI", color = Accent, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
                            Spacer(Modifier.height(7.dp))
                            Text("Keep your\nconversations alive.", fontSize = 30.sp,
                                lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Small reminders. Better messages.", color = Muted, fontSize = 14.sp)
                        }
                        Box(
                            Modifier.size(58.dp).background(
                                Brush.linearGradient(listOf(Accent, Color(0xFF5A48B8))), CircleShape
                            ),
                            contentAlignment = Alignment.Center
                        ) { Text("S", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryCard("FRIENDS", friends.size.toString(), Modifier.weight(1f))
                        SummaryCard("REMINDERS", friends.count { it.reminderEnabled }.toString(), Modifier.weight(1f))
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your people", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(status, fontSize = 12.sp, color = Muted)
                        }
                        OutlinedButton(onClick = { settingsOpen = true }, shape = RoundedCornerShape(14.dp)) {
                            Text("AI settings")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { addOpen = true }, shape = RoundedCornerShape(14.dp)) {
                            Text("+ Add")
                        }
                    }
                }
                if (friends.isEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🔥", fontSize = 38.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("No friends added yet", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("Add a TikTok username and choose a daily reminder time.",
                                    color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { addOpen = true }) { Text("Add your first friend") }
                            }
                        }
                    }
                } else {
                    items(friends, key = { it.id }) { friend ->
                        FriendCard(
                            friend = friend,
                            onToggle = { enabled ->
                                scope.launch(Dispatchers.IO) { dao.setReminderEnabled(friend.id, enabled) }
                            },
                            onDelete = {
                                scope.launch(Dispatchers.IO) { dao.delete(friend) }
                            },
                            onMarkSent = {
                                scope.launch(Dispatchers.IO) {
                                    val today = LocalDate.now()
                                    dao.markSentToday(friend.id, today.toString(), today.minusDays(1).toString())
                                }
                            },
                            onOpen = {
                                val ok = TikTokLauncher.openProfile(context, friend.username)
                                if (!ok) Toast.makeText(context, "Could not open this TikTok profile.", Toast.LENGTH_SHORT).show()
                            },
                            onGenerate = {
                                val apiKey = prefs.getString("gemini_api_key", "").orEmpty()
                                selectedFriend = friend
                                isGenerating = true
                                generatedMessage = ""
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            GeminiClient.generateMessage(apiKey, friend.displayName, friend.username, tone)
                                        }
                                        generatedMessage = result
                                    } catch (e: Exception) {
                                        generatedMessage = "ERROR: " + (e.message ?: "Could not generate a message.")
                                    } finally {
                                        isGenerating = false
                                    }
                                }
                            }
                        )
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF211E38)),
                        shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(17.dp)) {
                            Text("MESSAGE STUDIO", color = Accent, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("A message that sounds like you.", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Choose a friend and let Gemini draft a short, natural opener.",
                                color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Text("TikTok handoff", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Streak Saver prepares the message, copies it, and opens the friend’s TikTok profile. Review and send it yourself in TikTok.",
                        color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    if (addOpen) {
        AddFriendDialog(onDismiss = { addOpen = false }) { name, username, hour, minute ->
            scope.launch(Dispatchers.IO) {
                dao.insert(FriendEntity(displayName = name, username = username,
                    reminderHour = hour, reminderMinute = minute))
            }
            addOpen = false
        }
    }

    if (settingsOpen) {
        var key by remember { mutableStateOf(prefs.getString("gemini_api_key", "").orEmpty()) }
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Gemini settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Paste your Gemini API key. It is stored only in this app’s private preferences.",
                        color = Muted, fontSize = 13.sp)
                    OutlinedTextField(value = key, onValueChange = { key = it },
                        label = { Text("Gemini API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {
                        tone = if (tone == "Casual and friendly") "Funny and playful" else "Casual and friendly"
                    }) { Text("Message style: " + tone) }
                    Text("Create a key at aistudio.google.com/app/apikey", color = Muted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    prefs.edit().putString("gemini_api_key", key.trim()).apply()
                    settingsOpen = false
                    Toast.makeText(context, "Gemini key saved on this device.", Toast.LENGTH_SHORT).show()
                }) { Text("Save key") }
            },
            dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("Cancel") } }
        )
    }

    selectedFriend?.let { friend ->
        AlertDialog(
            onDismissRequest = { if (!isGenerating) selectedFriend = null },
            title = { Text("Message for " + friend.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isGenerating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Gemini is writing…", color = Muted)
                        }
                    } else {
                        Text(generatedMessage.removePrefix("ERROR: "), fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedFriend = null }, enabled = !isGenerating) { Text("Close") }
            },
            dismissButton = {
                if (!isGenerating && generatedMessage.isNotBlank() && !generatedMessage.startsWith("ERROR:")) {
                    Row {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Streak Saver message", generatedMessage))
                            Toast.makeText(context, "Message copied. Open TikTok to send it.", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy") }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Streak Saver message", generatedMessage))
                            TikTokLauncher.openProfile(context, friend.username)
                            selectedFriend = null
                        }) { Text("Copy + TikTok") }
                    }
                } else {
                    TextButton(onClick = { settingsOpen = true; selectedFriend = null }, enabled = !isGenerating) {
                        Text("AI settings")
                    }
                }
            }
        )
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun FriendCard(
    friend: FriendEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onMarkSent: () -> Unit,
    onOpen: () -> Unit,
    onGenerate: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(friend.displayName.take(1).uppercase(), color = Accent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(friend.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("@" + friend.username, color = Muted, fontSize = 12.sp)
                }
                Switch(checked = friend.reminderEnabled, onCheckedChange = onToggle)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DAILY REMINDER", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text(String.format("%02d:%02d", friend.reminderHour, friend.reminderMinute),
                    color = Accent, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔥 ${friend.streakDays}-day app-tracked streak", color = Accent, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMarkSent) { Text("Mark sent today") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGenerate, modifier = Modifier.weight(1f)) { Text("AI message") }
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open TikTok") }
                TextButton(onClick = onDelete) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun AddFriendDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("20") }
    var minute by remember { mutableStateOf("00") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Friend name") }, singleLine = true)
                OutlinedTextField(username, { username = it.removePrefix("@") },
                    label = { Text("TikTok username") }, singleLine = true,
                    supportingText = { Text("Do not include the @ symbol.") })
                Text("Daily reminder time (24-hour clock)", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f), label = { Text("Hour") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f), label = { Text("Minute") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                val h = hour.toIntOrNull()
                val m = minute.toIntOrNull()
                if (name.isBlank()) error = "Enter a friend name."
                else if (username.isBlank()) error = "Enter their TikTok username."
                else if (h == null || h !in 0..23 || m == null || m !in 0..59) error = "Enter a valid time."
                else onSave(name.trim(), username.trim().removePrefix("@"), h, m)
            }) { Text("Save friend") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
