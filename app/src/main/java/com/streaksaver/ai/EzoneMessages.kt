package com.streaksaver.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private val MsgBg = Color(0xFF050505)
private val MsgSurface = Color(0xFF111111)
private val MsgSurface2 = Color(0xFF202020)
private val MsgText = Color(0xFFFFFFFF)
private val MsgMuted = Color(0xFFAAAAAA)
private val MsgRed = Color(0xFFFFFFFF)
private val MsgLine = Color(0xFF292929)

private data class EzoneDirectMessage(
    val id: String,
    val sender: String,
    val receiver: String,
    val body: String,
    val created: String,
    val seenAt: String
)

@Composable
fun MessagesScreen(context: Context, onOpenChat: (String, String) -> Unit, onNewMessage: () -> Unit) {
    var messages by remember { mutableStateOf<List<EzoneDirectMessage>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, EzoneProfile>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val me = EzoneSession.userId
    LaunchedEffect(Unit) {
        while (true) { delay(10000); refresh++ }
    }
    LaunchedEffect(refresh) {
        loading = true
        EzoneBackend.request(context, "GET",
            "/rest/v1/direct_messages?select=id,sender_id,receiver_id,body,created_at,seen_at&or=(sender_id.eq." + me + ",receiver_id.eq." + me + ")&order=created_at.desc&limit=300",
            EzoneSession.accessToken) { response ->
            loading = false
            if (response.ok) {
                val array = JSONArray(response.body)
                messages = (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    EzoneDirectMessage(item.optString("id"), item.optString("sender_id"), item.optString("receiver_id"), item.optString("body"), item.optString("created_at"), item.optString("seen_at"))
                }
                val ids = messages.map { if (it.sender == me) it.receiver else it.sender }.distinct()
                if (ids.isNotEmpty()) {
                    EzoneBackend.request(context, "GET",
                        "/rest/v1/profiles?select=id,username,display_name,bio,avatar_url&id=in.(" + ids.joinToString(",") + ")",
                        EzoneSession.accessToken) { result ->
                        if (result.ok) profiles = EzoneBackend.parseProfiles(result.body).associateBy { it.id }
                    }
                }
                error = ""
            } else error = EzoneBackend.errorMessage(response, "Could not load your inbox.")
        }
    }
    Column(Modifier.fillMaxSize().background(MsgBg)) {
        Box(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Text("Messages", color = MsgText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("✎", color = MsgText, fontSize = 21.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).clickable { onNewMessage() })
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            singleLine = true,
            placeholder = { Text("Search messages", color = MsgMuted, fontSize = 12.sp) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MsgText, unfocusedTextColor = MsgText,
                focusedBorderColor = MsgLine, unfocusedBorderColor = MsgLine,
                cursorColor = MsgText, focusedContainerColor = MsgSurface, unfocusedContainerColor = MsgSurface
            )
        )
        HorizontalDivider(color = MsgLine)
        if (loading && messages.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MsgRed)
            }
        } else if (error.isNotBlank()) {
            Text(error, color = Color(0xFFFF9D9D), modifier = Modifier.padding(18.dp))
        } else {
            val latest = messages.groupBy { if (it.sender == me) it.receiver else it.sender }
                .mapValues { it.value.maxByOrNull { msg -> msg.created } }
                .entries.sortedByDescending { it.value?.created.orEmpty() }
                .filter { entry -> searchQuery.isBlank() || (profiles[entry.key]?.displayName.orEmpty() + " " + profiles[entry.key]?.username.orEmpty() + " " + entry.value?.body.orEmpty()).contains(searchQuery, true) }
            if (latest.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your inbox is quiet", color = MsgText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Visit a member's profile and start a conversation.", color = MsgMuted, fontSize = 12.sp)
                }
            } else {
                LazyColumn {
                    items(latest, key = { it.key }) { entry ->
                        val person = profiles[entry.key]
                        val unreadCount = messages.count {
                            it.sender == entry.key && it.receiver == me && it.seenAt.isBlank()
                        }
                        Row(Modifier.fillMaxWidth().clickable {
                            onOpenChat(entry.key, person?.displayName ?: "Ezone member")
                        }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(48.dp).clip(CircleShape).background(MsgSurface2), contentAlignment = Alignment.Center) {
                                    Text(person?.displayName?.take(1)?.uppercase() ?: "E", color = MsgText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                }
                                if (unreadCount > 0) {
                                    Badge(Modifier.align(Alignment.TopEnd)) {
                                        Text(if (unreadCount > 99) "99+" else unreadCount.toString(), fontSize = 9.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(person?.displayName ?: "Ezone member", color = MsgText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    (if (entry.value?.sender == me) "You: " else "") + entry.value?.body.orEmpty(),
                                    color = if (unreadCount > 0) MsgText else MsgMuted,
                                    fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 12.sp, maxLines = 1
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(entry.value?.created?.substringAfter("T")?.take(5).orEmpty(), color = MsgMuted, fontSize = 10.sp)
                                if (entry.value?.sender == me && entry.value?.seenAt?.isNotBlank() == true) {
                                    Text("Seen", color = MsgMuted, fontSize = 9.sp)
                                }
                            }
                        }
                        HorizontalDivider(color = MsgLine)
                    }
                }
            }
        }
    }
}

@Composable
fun DirectChatDialog(context: Context, otherUserId: String, otherUserName: String, onDismiss: () -> Unit) {
    var messages by remember(otherUserId) { mutableStateOf<List<EzoneDirectMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val me = EzoneSession.userId
    LaunchedEffect(otherUserId, refresh) {
        EzoneBackend.request(
            context, "PATCH",
            "/rest/v1/direct_messages?sender_id=eq." + otherUserId + "&receiver_id=eq." + me + "&seen_at=is.null",
            EzoneSession.accessToken,
            JSONObject().put("seen_at", Instant.now().toString()),
            "return=minimal"
        ) { }
    }
    LaunchedEffect(otherUserId) {
        while (true) { delay(3500); refresh++ }
    }
    LaunchedEffect(otherUserId, refresh) {
        loading = true
        val filter = "or=(and(sender_id.eq." + me + ",receiver_id.eq." + otherUserId + "),and(sender_id.eq." + otherUserId + ",receiver_id.eq." + me + "))"
        EzoneBackend.request(context, "GET",
            "/rest/v1/direct_messages?select=id,sender_id,receiver_id,body,created_at,seen_at&" + filter + "&order=created_at.asc&limit=300",
            EzoneSession.accessToken) { response ->
            loading = false
            if (response.ok) {
                val array = JSONArray(response.body)
                messages = (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    EzoneDirectMessage(item.optString("id"), item.optString("sender_id"), item.optString("receiver_id"), item.optString("body"), item.optString("created_at"), item.optString("seen_at"))
                }
                error = ""
            } else error = EzoneBackend.errorMessage(response, "Could not load this chat.")
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 420.dp, max = 720.dp)
                .clip(RoundedCornerShape(18.dp)).background(MsgBg).padding(16.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MsgSurface2), contentAlignment = Alignment.Center) {
                    Text(otherUserName.take(1).uppercase(), color = MsgRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(otherUserName, color = MsgText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Private Ezone chat", color = MsgMuted, fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) { Text("✕", color = MsgMuted, fontSize = 18.sp) }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = MsgLine)
            val chatScroll = rememberScrollState()
            LaunchedEffect(messages.size) { chatScroll.animateScrollTo(chatScroll.maxValue) }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp).verticalScroll(chatScroll),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (loading && messages.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MsgRed, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                if (messages.isEmpty() && !loading) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 45.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👋", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Start the conversation", color = MsgText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Send a message to say hello.", color = MsgMuted, fontSize = 11.sp)
                    }
                }
                messages.forEach { msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.sender == me) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier.widthIn(max = 270.dp).clip(
                                RoundedCornerShape(
                                    topStart = 18.dp, topEnd = 18.dp,
                                    bottomStart = if (msg.sender == me) 18.dp else 5.dp,
                                    bottomEnd = if (msg.sender == me) 5.dp else 18.dp
                                )
                            ).background(if (msg.sender == me) MsgRed else MsgSurface2)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(msg.body, color = if (msg.sender == me) Color.White else MsgText, fontSize = 13.sp, lineHeight = 18.sp)
                            Text(msg.created.substringAfter("T").take(5), color = if (msg.sender == me) Color(0xFFFFD9DE) else MsgMuted, fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.End).padding(top = 3.dp))
                        }
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9D9D), fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp)).background(MsgSurface).padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it.take(2000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…", color = MsgMuted, fontSize = 13.sp) },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MsgText, unfocusedTextColor = MsgText,
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        cursorColor = MsgRed
                    )
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    enabled = draft.trim().isNotEmpty() && !sending,
                    onClick = {
                        sending = true
                        val text = draft.trim()
                        val payload = JSONObject().put("sender_id", me).put("receiver_id", otherUserId).put("body", text)
                        EzoneBackend.request(context, "POST", "/rest/v1/direct_messages", EzoneSession.accessToken, payload, "return=minimal") { response ->
                            sending = false
                            if (response.ok) { draft = ""; refresh++ }
                            else error = EzoneBackend.errorMessage(response, "Message could not be sent.")
                        }
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MsgRed, contentColor = Color.White)
                ) { Text(if (sending) "…" else "➤", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
