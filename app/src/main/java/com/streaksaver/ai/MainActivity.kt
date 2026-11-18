package com.streaksaver.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF10121A)
private val Panel = Color(0xFF1B1F2B)
private val Violet = Color(0xFF9B8CFF)
private val Muted = Color(0xFF9AA1B5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StreakSaverApp() }
    }
}

@Composable
fun StreakSaverApp() {
    var friendName by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf(listOf("Best friend", "Gaming buddy")) }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Panel, primary = Violet,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(Modifier.height(28.dp))
                    Text("STREAK SAVER", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("Never lose the
conversation.", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Your streaks, organized.", color = Muted, fontSize = 15.sp)
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("FRIENDS", friends.size.toString(), Modifier.weight(1f))
                        StatCard("TODAY", "0 due", Modifier.weight(1f))
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Add a friend", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = friendName, onValueChange = { friendName = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                placeholder = { Text("TikTok username or nickname") }
                            )
                            Button(
                                onClick = { if (friendName.isNotBlank()) { friends = friends + friendName.trim(); friendName = "" } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Add friend") }
                        }
                    }
                }
                item { Text("YOUR FRIENDS", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
                items(friends.size) { index ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).background(Violet.copy(alpha = .18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                Text(friends[index].take(1).uppercase(), color = Violet, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(friends[index], fontWeight = FontWeight.SemiBold)
                                Text("No reminder scheduled", color = Muted, fontSize = 12.sp)
                            }
                            Text("•••", color = Muted)
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF25223B)), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("AI MESSAGE STUDIO", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Fresh message ideas, ready when you are.", fontWeight = FontWeight.SemiBold)
                            Text("AI suggestions and reminder scheduling are coming next.", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Text("TikTok sending stays under your control: this app prepares messages and opens TikTok for you to review and send.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
