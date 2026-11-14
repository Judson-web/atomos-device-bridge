package dev.atomos.bridge.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MessengerViolet = Color(0xFF9B8CFF)
private val MessengerSurface = Color(0xFF151A27)
private val MessengerMuted = Color(0xFF9299AC)

@Composable
fun MessagingInboxScreen(
    api: SupabaseMessengerApi,
    userId: String,
    storageStatus: String,
    onPeople: () -> Unit,
    onOpenConversation: (String) -> Unit
) {
    val nativeProtocolVersion = remember { NativeMlsBridge.protocolVersionOrNull() }
    var conversations by remember { mutableStateOf<List<MessengerConversationSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api, userId) {
        loading = true
        loadError = null
        try {
            api.observeConversationRows().collect {
                conversations = api.conversationSummaries()
                loading = false
                loadError = null
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Unable to load conversations."
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B12))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Messages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Private conversations, thoughtfully built.", style = MaterialTheme.typography.bodyMedium, color = MessengerMuted)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MessengerSurface)
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier.size(42.dp).background(Color(0xFF25213F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⌑", color = MessengerViolet, fontSize = 24.sp)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("MLS security setup", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This device can enroll and publish an MLS KeyPackage using its encrypted local store. New conversations can be created, but message sending stays disabled until MLS group creation, epoch persistence and message encryption are complete.",
                        color = MessengerMuted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp
                    )
                }
            }
        }

        Text("YOUR CONVERSATIONS", color = MessengerViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)

        when {
            loading -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MessengerViolet)
            }
            loadError != null -> Card(
                colors = CardDefaults.cardColors(containerColor = MessengerSurface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't load your inbox", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(loadError ?: "", color = MessengerMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            conversations.isEmpty() -> Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MessengerSurface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier.size(68.dp).background(Color(0xFF17182A), CircleShape).border(1.dp, Color(0xFF343052), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✉", color = MessengerViolet, fontSize = 30.sp)
                    }
                    Text("Your inbox starts here", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "You don't have any conversations yet. Find someone by username and send a contact request to get started.",
                        modifier = Modifier.fillMaxWidth(0.9f),
                        color = MessengerMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        lineHeight = 21.sp
                    )
                    Button(onClick = onPeople, modifier = Modifier.fillMaxWidth()) {
                        Text("Find people")
                    }
                }
            }
            else -> conversations.forEach { conversation ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenConversation(conversation.id) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MessengerSurface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).background(Color(0xFF25213F), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (conversation.kind == "group") "◎" else "◉", color = MessengerViolet, fontSize = 22.sp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                conversation.participantNames.ifBlank { if (conversation.kind == "group") "Group conversation" else "Direct conversation" },
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(if (conversation.kind == "group") "Group" else "Direct message", color = MessengerMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (conversation.unreadCount > 0) {
                            Surface(shape = CircleShape, color = MessengerViolet) {
                                Text(
                                    conversation.unreadCount.toString(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color(0xFF11111B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111521))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("NATIVE PROTOCOL RUNTIME", color = MessengerViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text(nativeProtocolVersion ?: "Native library not present in this build", color = Color(0xFFD8D9E2), style = MaterialTheme.typography.bodySmall)
                Text(storageStatus, color = MessengerMuted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                Text(
                    if (nativeProtocolVersion == null) "No messages can be sent in this build."
                    else "MLS group bootstrap and encrypted message send/receive are connected to the conversation screen. Native build and multi-device verification are still required.",
                    color = MessengerMuted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun ConversationSetupScreen(conversationId: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF090B12)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedButton(onClick = onBack) { Text("Back to chats") }
        Text("Conversation", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MessengerSurface)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Conversation created", color = MessengerViolet, fontWeight = FontWeight.Bold)
                Text("The conversation record is ready.", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    "The device is enrolled and its MLS KeyPackage is published. Atomos must still establish the MLS group, persist group epochs and verify the session before enabling message composition.",
                    color = MessengerMuted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
                Text("Conversation ID: $conversationId", color = Color(0xFF737B8E), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
