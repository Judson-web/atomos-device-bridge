package dev.atomos.bridge.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val InboxBackground = Color(0xFF090B12)
private val InboxSurface = Color(0xFF141927)
private val InboxRaised = Color(0xFF1A2030)
private val InboxAccent = Color(0xFFA99BFF)
private val InboxMuted = Color(0xFF8D96AA)

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
    var search by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var refreshNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(api, userId, refreshNonce) {
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

    val visibleConversations = conversations.filter { conversation ->
        val matchesSearch = conversation.participantNames.contains(search.trim(), ignoreCase = true)
        val matchesFilter = when (selectedFilter) {
            "Unread" -> conversation.unreadCount > 0
            "Groups" -> conversation.kind == "group"
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Column(
        modifier = Modifier.fillMaxSize().background(InboxBackground),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("atomos", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
                Text("MESSAGES  ·  PRIVATE BY DESIGN", color = InboxMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.25.sp)
            }
            Surface(
                modifier = Modifier.size(42.dp).clickable(onClick = onPeople),
                shape = CircleShape,
                color = Color(0xFF292442)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+", color = InboxAccent, fontSize = 27.sp, fontWeight = FontWeight.Light)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBubble("+", "New chat", true, Modifier.weight(1f), onPeople)
            StatusBubble("◎", "Contacts", false, Modifier.weight(1f), onPeople)
            StatusBubble("⌕", "Requests", false, Modifier.weight(1f), onPeople)
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text("Search conversations", color = InboxMuted) },
            leadingIcon = { Text("⌕", color = InboxAccent, fontSize = 22.sp) }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Unread", "Groups").forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${visibleConversations.size} chats",
                modifier = Modifier.align(Alignment.CenterVertically),
                color = InboxMuted,
                fontSize = 11.sp
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = InboxAccent)
            }
            loadError != null -> Card(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = InboxSurface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your chats couldn't load", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(loadError.orEmpty(), color = InboxMuted, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        loading = true
                        loadError = null
                        refreshNonce++
                    }) { Text("Try again") }
                }
            }
            visibleConversations.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(76.dp).background(Color(0xFF201D35), CircleShape)
                            .border(1.dp, Color(0xFF3C365E), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text("✉", color = InboxAccent, fontSize = 31.sp) }
                    Text(if (search.isBlank()) "A quieter inbox. For now." else "No matching conversations", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (search.isBlank()) "Start a private conversation by finding someone on Atomos." else "Try another name or clear your search.",
                        color = InboxMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (search.isBlank()) Button(onClick = onPeople) { Text("Start a conversation") }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(visibleConversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.id) }
                    )
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("ATOMOS  ·  OPENMLS ${nativeProtocolVersion ?: "PENDING"}", color = InboxAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(storageStatus, color = InboxMuted, fontSize = 10.sp)
                        Text("Private conversations, built one device at a time.", color = InboxMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBubble(
    symbol: String,
    label: String,
    highlighted: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier.size(48.dp).background(
                if (highlighted) Color(0xFF292442) else InboxRaised,
                CircleShape
            ).border(1.dp, if (highlighted) InboxAccent else Color(0xFF30384A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, color = if (highlighted) InboxAccent else Color(0xFFD8D9E4), fontSize = 21.sp)
        }
        Text(label, color = InboxMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConversationRow(
    conversation: MessengerConversationSummary,
    onClick: () -> Unit
) {
    val title = conversation.participantNames.ifBlank {
        if (conversation.kind == "group") "Group conversation" else "Direct conversation"
    }
    val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val timestamp = remember(conversation.updatedAt) {
        runCatching {
            Instant.parse(conversation.updatedAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        }.getOrDefault("")
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(54.dp).background(
                if (conversation.kind == "group") Color(0xFF292442) else Color(0xFF202A3B),
                CircleShape
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(initial, color = if (conversation.kind == "group") InboxAccent else Color(0xFFB8D4FF), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            if (conversation.kind != "group") {
                Box(Modifier.align(Alignment.BottomEnd).size(13.dp).background(InboxBackground, CircleShape).padding(2.dp).background(Color(0xFF596477), CircleShape))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(timestamp, color = if (conversation.unreadCount > 0) InboxAccent else InboxMuted, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (conversation.kind == "group") "Group conversation" else "Encrypted conversation",
                    color = InboxMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (conversation.unreadCount > 0) {
                    Box(
                        Modifier.padding(start = 8.dp).background(InboxAccent, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(conversation.unreadCount.coerceAtMost(99).toString(), color = Color(0xFF151321), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(0.5.dp).background(Color(0xFF222A39)))
}
