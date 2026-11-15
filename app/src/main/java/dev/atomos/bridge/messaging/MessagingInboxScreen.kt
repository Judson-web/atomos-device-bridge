package dev.atomos.bridge.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalDate
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
    var conversations by remember { mutableStateOf<List<MessengerConversationSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var refreshNonce by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(api, userId, refreshNonce) {
        loading = true
        loadError = null
        try {
            conversations = api.conversationSummaries()
            loading = false
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
            "Direct" -> conversation.kind != "group"
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Column(
        modifier = Modifier.fillMaxSize().background(InboxBackground)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("atomos", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp)
                Text("MESSAGES", color = InboxAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            }
            Surface(
                modifier = Modifier.size(46.dp).clickable(onClick = onPeople),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF292442),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49416F))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+", color = InboxAccent, fontSize = 29.sp, fontWeight = FontWeight.Light)
                }
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text("Search people and conversations", color = InboxMuted) },
            leadingIcon = { Text("⌕", color = InboxAccent, fontSize = 23.sp) }
        )

        if (!storageStatus.contains("ready", ignoreCase = true)) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                color = Color(0xFF2A241C),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    storageStatus,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    color = Color(0xFFE8C58B),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Conversations", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${visibleConversations.size}", color = InboxMuted, fontSize = 12.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("All", "Unread", "Direct", "Groups").forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter, fontSize = 12.sp) },
                    shape = RoundedCornerShape(14.dp)
                )
            }
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
                    Text(
                        if (search.isBlank()) "Your next conversation starts here" else "No matching conversations",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        if (search.isBlank()) "Find people on Atomos and start chatting. Your conversations will appear here."
                        else "Try another name or switch the conversation filter.",
                        color = InboxMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    if (search.isBlank()) Button(onClick = onPeople) { Text("New message") }
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp)
            ) {
                items(visibleConversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.id) },
                        onCopyName = { clipboard.setText(AnnotatedString(conversation.participantNames.ifBlank { "Conversation" })) }
                    )
                }
            }
            Box {
                Text(
                    "⋮",
                    color = InboxMuted,
                    fontSize = 22.sp,
                    modifier = Modifier.clickable { menuExpanded = true }.padding(horizontal = 5.dp, vertical = 8.dp)
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Open conversation") },
                        onClick = { menuExpanded = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy contact name") },
                        onClick = { menuExpanded = false; onCopyName() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: MessengerConversationSummary,
    onClick: () -> Unit,
    onCopyName: () -> Unit
) {
    var menuExpanded by remember(conversation.id) { mutableStateOf(false) }
    val title = conversation.participantNames.ifBlank {
        if (conversation.kind == "group") "Group conversation" else "Direct conversation"
    }
    val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val timestamp = remember(conversation.updatedAt) {
        runCatching {
            val dateTime = Instant.parse(conversation.updatedAt).atZone(ZoneId.systemDefault())
            if (dateTime.toLocalDate() == LocalDate.now()) {
                dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
            } else {
                dateTime.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
            }
        }.getOrDefault("")
    }
    val avatarColor = if (conversation.kind == "group") Color(0xFF292442) else Color(0xFF202A3B)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = InboxSurface,
        shape = RoundedCornerShape(19.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(52.dp).background(avatarColor, CircleShape)
                    .border(1.dp, if (conversation.kind == "group") Color(0xFF4A416F) else Color(0xFF34435B), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(initial, color = if (conversation.kind == "group") InboxAccent else Color(0xFFB8D4FF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = Color.White,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(timestamp, color = if (conversation.unreadCount > 0) InboxAccent else InboxMuted, fontSize = 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (conversation.kind == "group") "Group conversation" else "Private conversation",
                        color = InboxMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conversation.unreadCount > 0) {
                        Box(
                            Modifier.padding(start = 8.dp).background(InboxAccent, CircleShape)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(conversation.unreadCount.coerceAtMost(99).toString(), color = Color(0xFF151321), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
