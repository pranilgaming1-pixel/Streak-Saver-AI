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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private val MsgBg = Color(0xFF050505)
private val MsgSurface = Color(0xFF111111)
private val MsgSurface2 = Color(0xFF1B1B1B)
private val MsgText = Color(0xFFFFFFFF)
private val MsgMuted = Color(0xFFAAAAAA)
private val MsgRed = Color(0xFFED233C)
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
fun MessagesScreen(context: Context, onOpenChat: (String, String) -> Unit) {
    var messages by remember { mutableStateOf<List<EzoneDirectMessage>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, EzoneProfile>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val me = EzoneSession.userId
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
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Messages", color = MsgText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Your private conversations", color = MsgMuted, fontSize = 12.sp)
            }
            TextButton(onClick = { refresh++ }) { Text("Refresh", color = MsgRed) }
        }
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
            if (latest.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your inbox is quiet", color = MsgText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Visit a member's profile and start a conversation.", color = MsgMuted, fontSize = 12.sp)
                }
            } else {
                LazyColumn {
                    items(latest, key = { it.key }) { entry ->
                        val person = profiles[entry.key]
                        Row(Modifier.fillMaxWidth().clickable {
                            onOpenChat(entry.key, person?.displayName ?: "Ezone member")
                        }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(MsgSurface2), contentAlignment = Alignment.Center) {
                                Text(person?.displayName?.take(1)?.uppercase() ?: "E", color = MsgRed, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(person?.displayName ?: "Ezone member", color = MsgText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    if (entry.value?.sender == me && entry.value?.seenAt?.isNotBlank() == true)
                                        "Seen · " + entry.value?.body.orEmpty()
                                    else entry.value?.body.orEmpty(),
                                    color = if (entry.value?.sender == me && entry.value?.seenAt?.isNotBlank() == true) MsgRed else MsgMuted,
                                    fontSize = 12.sp, maxLines = 1
                                )
                            }
                            Text(entry.value?.created?.substringAfter("T")?.take(5).orEmpty(), color = MsgMuted, fontSize = 10.sp)
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
        loading = true
        EzoneBackend.request(
            context, "PATCH",
            "/rest/v1/direct_messages?sender_id=eq." + otherUserId + "&receiver_id=eq." + me + "&seen_at=is.null",
            EzoneSession.accessToken,
            JSONObject().put("seen_at", Instant.now().toString()),
            "return=minimal"
        ) { }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MsgSurface,
        title = {
            Column {
                Text(otherUserName, color = MsgText, fontWeight = FontWeight.Bold)
                Text("Private Ezone chat", color = MsgMuted, fontSize = 11.sp)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (loading && messages.isEmpty()) Text("Loading messages…", color = MsgMuted)
                Column(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 330.dp).verticalScroll(rememberScrollState())) {
                    messages.forEach { msg ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.sender == me) Arrangement.End else Arrangement.Start) {
                            Box(Modifier.padding(vertical = 4.dp).widthIn(max = 240.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(if (msg.sender == me) MsgRed else MsgSurface2)
                                .padding(horizontal = 13.dp, vertical = 9.dp)) {
                                Text(msg.body, color = if (msg.sender == me) Color(0xFF061018) else MsgText, fontSize = 13.sp)
                            }
                        }
                    }
                    if (messages.isEmpty() && !loading) Text("Say hello to start chatting.", color = MsgMuted, fontSize = 12.sp)
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF9D9D), fontSize = 11.sp)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) },
                        modifier = Modifier.weight(1f), placeholder = { Text("Message…", color = MsgMuted) },
                        maxLines = 3, colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MsgText, unfocusedTextColor = MsgText,
                            focusedBorderColor = MsgRed, unfocusedBorderColor = MsgLine,
                            focusedContainerColor = MsgBg, unfocusedContainerColor = MsgBg
                        ))
                    Spacer(Modifier.width(6.dp))
                    Button(enabled = draft.trim().isNotEmpty() && !sending, onClick = {
                        sending = true
                        val text = draft.trim()
                        val payload = JSONObject().put("sender_id", me).put("receiver_id", otherUserId).put("body", text)
                        EzoneBackend.request(context, "POST", "/rest/v1/direct_messages", EzoneSession.accessToken, payload, "return=minimal") { response ->
                            sending = false
                            if (response.ok) { draft = ""; refresh++ }
                            else error = EzoneBackend.errorMessage(response, "Message could not be sent.")
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = MsgRed, contentColor = Color(0xFF061018))) {
                        Text(if (sending) "…" else "Send")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = MsgRed) } }
    )
}
