package dev.atomos.bridge.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class RenderedMessage(val id: String, val text: String, val mine: Boolean)

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
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading encrypted conversation…") }

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
                    messages.add(RenderedMessage(row.id, text, row.senderUserId == userId))
                }
            }
            runCatching { api.markMessageRead(row.id, userId) }
        }
    }

    LaunchedEffect(conversationId, deviceId) {
        if (deviceId.isBlank()) {
            status = "This device is not enrolled for secure messaging."
            loading = false
            return@LaunchedEffect
        }
        try {
            val initial = withContext(Dispatchers.IO) { api.encryptedMessages(conversationId) }
            consume(initial)
            status = if (NativeMlsBridge.hasGroup(conversationId)) "End-to-end encrypted with MLS" else "Waiting for this device's secure group setup."
            loading = false
            api.observeMessages(conversationId).collect { rows ->
                try {
                    consume(rows)
                    status = if (NativeMlsBridge.hasGroup(conversationId)) "End-to-end encrypted with MLS" else "Waiting for this device's secure group setup."
                } catch (e: Exception) {
                    status = e.message ?: "Unable to process encrypted messages."
                }
            }
        } catch (e: Exception) {
            status = e.message ?: "Unable to load encrypted conversation."
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF090B12)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text("Conversation", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(status, color = Color(0xFF9B8CFF), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (loading) {
            CircularProgressIndicator(color = Color(0xFF9B8CFF))
        } else if (messages.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151A27)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    "Your messages will appear here after the other device joins the secure conversation.",
                    modifier = Modifier.padding(18.dp),
                    color = Color(0xFF9299AC),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.mine) Color(0xFF342E5C) else Color(0xFF151A27)
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            message.text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
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
                placeholder = { Text("Write a message") },
                maxLines = 4,
                enabled = !sending && NativeMlsBridge.hasGroup(conversationId)
            )
            Button(
                enabled = draft.isNotBlank() && !sending && NativeMlsBridge.hasGroup(conversationId),
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
                            api.sendMlsMessage(conversationId, userId, deviceId, messageId, ciphertext)
                            withContext(Dispatchers.IO) {
                                check(NativeMlsBridge.markMessageSent(conversationId, messageId)) {
                                    "Message sent, but local retry state could not be cleared."
                                }
                            }
                            messages.add(RenderedMessage(messageId, text, true))
                            draft = ""
                            status = "End-to-end encrypted with MLS"
                        } catch (e: Exception) {
                            status = e.message ?: "Message could not be sent."
                        } finally {
                            sending = false
                        }
                    }
                }
            ) {
                Text(if (sending) "…" else "Send")
            }
        }
        Text("Message contents are encrypted on this device before upload. Attachments are not enabled yet.", color = Color(0xFF737B8E), style = MaterialTheme.typography.labelSmall)
    }
}
