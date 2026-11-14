package dev.atomos.bridge.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class RenderedMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val time: String
)

private val ChatBackground = Color(0xFF090B12)
private val ChatPanel = Color(0xFF151B29)
private val ChatAccent = Color(0xFFA99BFF)
private val ChatMuted = Color(0xFF8993A8)
private val ChatOutgoing = Color(0xFF39335F)
private val ChatIncoming = Color(0xFF1A2231)

@Composable
fun ConversationSetupScreen(
    conversationId: String,
    api: SupabaseMessengerApi,
    userId: String,
    deviceId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val messages = remember(conversationId) { mutableStateListOf<RenderedMessage>() }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecting securely…") }
    var conversationTitle by remember(conversationId) { mutableStateOf("Conversation") }
    val secureGroupReady = NativeMlsBridge.hasGroup(conversationId)

    suspend fun consume(rows: List<MessengerCiphertextRow>) {
        rows.sortedBy { it.createdAt }.forEach { row ->
            if (messages.any { it.id == row.id }) return@forEach
            val clear = if (row.senderDeviceId == deviceId) {
                withContext(Dispatchers.IO) {
                    NativeMlsBridge.cachedPlaintext(conversationId, row.id)
                }
            } else {
                val encoded = row.ciphertext.removePrefix("\\x")
                withContext(Dispatchers.IO) {
                    NativeMlsBridge.processIncomingMessage(conversationId, row.id, encoded)?.plaintext
                }
            }
            if (clear != null) {
                val text = runCatching { clear.toString(Charsets.UTF_8) }.getOrDefault("")
                if (text.isNotBlank()) {
                    messages.add(
                        RenderedMessage(
                            row.id,
                            text,
                            row.senderUserId == userId,
                            row.createdAt.substringAfter("T", "").take(5)
                        )
                    )
                }
            }
            if (row.senderUserId != userId) {
                runCatching { api.markMessageRead(row.id, userId) }
            }
        }
    }

    LaunchedEffect(conversationId, deviceId) {
        if (deviceId.isBlank()) {
            status = "This device is not enrolled for secure messaging."
            loading = false
            return@LaunchedEffect
        }
        try {
            conversationTitle = withContext(Dispatchers.IO) {
                api.conversationSummaries().firstOrNull { it.id == conversationId }?.participantNames
                    ?.ifBlank { "Conversation" } ?: "Conversation"
            }
            val initial = withContext(Dispatchers.IO) { api.encryptedMessages(conversationId) }
            consume(initial)
            status = if (NativeMlsBridge.hasGroup(conversationId)) {
                "End-to-end encrypted · MLS"
            } else {
                "Waiting for secure group setup"
            }
            loading = false
            api.observeMessages(conversationId).collect { rows ->
                try {
                    consume(rows)
                    status = if (NativeMlsBridge.hasGroup(conversationId)) {
                        "End-to-end encrypted · MLS"
                    } else {
                        "Waiting for secure group setup"
                    }
                } catch (e: Exception) {
                    status = e.message ?: "Unable to process encrypted messages."
                }
            }
        } catch (e: Exception) {
            status = e.message ?: "Unable to load encrypted conversation."
            loading = false
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(ChatBackground, Color(0xFF0D1220), ChatBackground)
            )
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(ChatPanel).padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(Color(0xFF222A3B), CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = Color.White, fontSize = 31.sp, textAlign = TextAlign.Center)
            }
            Box(
                Modifier.size(43.dp).background(Color(0xFF292442), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(conversationTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "A", color = ChatAccent, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conversationTitle, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (secureGroupReady) Color(0xFF70D7AE) else Color(0xFFE0B56B), CircleShape))
                    Text(status, color = ChatMuted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = ChatAccent
                )
            } else if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Box(
                        Modifier.size(68.dp).background(Color(0xFF211E38), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✦", color = ChatAccent, fontSize = 28.sp)
                    }
                    Text("This is the beginning", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (secureGroupReady) {
                            "This device has joined the MLS group. Messages are encrypted on-device before upload."
                        } else {
                            "Secure group setup is still pending. No message can be sent until this device has joined the MLS group."
                        },
                        color = ChatMuted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(0.84f),
                            horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start
                        ) {
                            Surface(
                                color = if (message.mine) ChatOutgoing else ChatIncoming,
                                shape = RoundedCornerShape(
                                    topStart = 19.dp,
                                    topEnd = 19.dp,
                                    bottomEnd = if (message.mine) 5.dp else 19.dp,
                                    bottomStart = if (message.mine) 19.dp else 5.dp
                                ),
                                shadowElevation = 1.dp
                            ) {
                                Column(Modifier.padding(start = 13.dp, end = 11.dp, top = 9.dp, bottom = 6.dp)) {
                                    Text(message.text, color = Color(0xFFF4F3FA), style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp)
                                    Row(
                                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(message.time, color = Color(0xFFB8B2D2), fontSize = 9.sp)
                                        if (message.mine) Text("✓✓", color = ChatAccent, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().background(ChatPanel).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!secureGroupReady) {
                Text(
                    "Secure group setup is not complete yet. Message sending is locked until the MLS session is ready.",
                    color = Color(0xFFE0B56B),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 5.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(4000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message", color = ChatMuted) },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 5,
                    enabled = !sending && secureGroupReady
                )
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) scope.launch {
                            sending = true
                            try {
                                val messageId = UUID.randomUUID().toString()
                                val ciphertext = withContext(Dispatchers.IO) {
                                    NativeMlsBridge.createApplicationMessage(
                                        conversationId, deviceId, messageId, text.toByteArray(Charsets.UTF_8)
                                    )
                                } ?: error("MLS encryption failed. The secure group may need to be initialized again.")
                                api.sendMlsMessage(
                                    conversationId = conversationId,
                                    senderUserId = userId,
                                    senderDeviceId = deviceId,
                                    messageHex = ciphertext,
                                    messageId = messageId
                                )
                                withContext(Dispatchers.IO) {
                                    check(NativeMlsBridge.markMessageSent(conversationId, messageId)) {
                                        "Message sent, but local retry state could not be cleared."
                                    }
                                }
                                messages.add(
                                    RenderedMessage(
                                        messageId,
                                        text,
                                        true,
                                        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                                    )
                                )
                                draft = ""
                                status = "End-to-end encrypted · MLS"
                            } catch (e: Exception) {
                                status = e.message ?: "Message could not be sent."
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = draft.isNotBlank() && !sending && NativeMlsBridge.hasGroup(conversationId),
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatAccent, contentColor = Color(0xFF171426))
                ) {
                    Text(if (sending) "…" else "➤", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
