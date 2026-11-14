package dev.atomos.bridge.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MessengerPageBackground = Color(0xFF090B12)
private val MessengerPageSurface = Color(0xFF151A27)
private val MessengerPageMuted = Color(0xFF9299AC)
private val MessengerPageAccent = Color(0xFF9B8CFF)

@Composable
fun PeopleDiscoveryScreen(
    api: SupabaseMessengerApi,
    currentUserId: String,
    currentDeviceId: String,
    onConversationCreated: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var foundProfile by remember { mutableStateOf<MessengerProfile?>(null) }
    var incoming by remember { mutableStateOf<List<Pair<MessengerContactRequest, MessengerProfile>>>(emptyList()) }
    var accepted by remember { mutableStateOf<List<Pair<MessengerContactRequest, MessengerProfile>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun reloadContacts() {
        loading = true
        try {
            val incomingRows = api.incomingContactRequests(currentUserId)
            incoming = incomingRows.mapNotNull { request ->
                api.profileById(request.requesterId)?.let { request to it }
            }
            val acceptedRows = api.acceptedContactRequests(currentUserId)
            accepted = acceptedRows.mapNotNull { request ->
                val otherId = if (request.requesterId == currentUserId) request.targetId else request.requesterId
                api.profileById(otherId)?.let { request to it }
            }
        } catch (e: Exception) {
            status = e.message ?: "Unable to load contacts."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(currentUserId) {
        try {
            api.observeContactRequests().collect {
                reloadContacts()
            }
        } catch (e: Exception) {
            status = e.message ?: "Live contact updates are unavailable."
            reloadContacts()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MessengerPageBackground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("People", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Text("Find people by their Atomos username.", color = MessengerPageMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MessengerPageSurface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FIND SOMEONE", color = MessengerPageAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(33) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Atomos username") },
                    placeholder = { Text("e.g. judson_01") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        scope.launch {
                            searching = true
                            status = null
                            try {
                                foundProfile = api.searchProfile(username)
                                if (foundProfile == null) status = "No account found with that username."
                            } catch (e: Exception) {
                                status = e.message ?: "Search failed."
                            } finally {
                                searching = false
                            }
                        }
                    },
                    enabled = !searching && username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (searching) "Searching…" else "Search username")
                }
                foundProfile?.let { profile ->
                    if (profile.userId != currentUserId) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(profile.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("@${profile.username}", color = MessengerPageMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        api.sendContactRequest(currentUserId, profile.userId)
                                        status = "Contact request sent to @${profile.username}."
                                    } catch (e: Exception) {
                                        status = e.message ?: "Unable to send request."
                                    }
                                }
                            }) { Text("Add") }
                        }
                    }
                }
                status?.let { Text(it, color = MessengerPageMuted, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Text("CONTACT REQUESTS", color = MessengerPageAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (loading) {
            CircularProgressIndicator(color = MessengerPageAccent)
        } else if (incoming.isEmpty()) {
            Text("No pending requests.", color = MessengerPageMuted, style = MaterialTheme.typography.bodySmall)
        } else incoming.forEach { (request, profile) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MessengerPageSurface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("@${profile.username}", color = MessengerPageMuted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    api.respondToContactRequest(request.id, true)
                                    status = "Contact request accepted."
                                    reloadContacts()
                                } catch (e: Exception) { status = e.message ?: "Unable to accept request." }
                            }
                        }) { Text("Accept") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                try {
                                    api.respondToContactRequest(request.id, false)
                                    status = "Contact request declined."
                                    reloadContacts()
                                } catch (e: Exception) { status = e.message ?: "Unable to decline request." }
                            }
                        }) { Text("Decline") }
                    }
                }
            }
        }

        Text("YOUR CONTACTS", color = MessengerPageAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (accepted.isEmpty()) {
            Text("Accepted contacts will appear here.", color = MessengerPageMuted, style = MaterialTheme.typography.bodySmall)
        } else accepted.forEach { (_, profile) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MessengerPageSurface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(40.dp).background(Color(0xFF25213F), CircleShape), contentAlignment = Alignment.Center) {
                        Text(profile.displayName.take(1).uppercase(), color = MessengerPageAccent, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("@${profile.username}", color = MessengerPageMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = currentDeviceId.isNotBlank(),
                        onClick = {
                            scope.launch {
                                try {
                                    val conversationId = api.createDirectConversation(profile.userId)
                                    val claimed = api.claimConversationBootstrap(conversationId, currentDeviceId)
                                    if (claimed) {
                                        try {
                                            val recipientPackages = api.consumeConversationKeyPackages(
                                                conversationId,
                                                profile.userId,
                                                currentDeviceId
                                            )
                                            val ownOtherDevicePackages = api.consumeConversationKeyPackages(
                                                conversationId,
                                                currentUserId,
                                                currentDeviceId
                                            )
                                            val packageHex = (recipientPackages + ownOtherDevicePackages)
                                                .map { it.packageHex() }
                                            check(packageHex.isNotEmpty()) {
                                                "No active MLS KeyPackages are available. Ask the other person to open Atomos and try again."
                                            }
                                            val group = withContext(Dispatchers.IO) {
                                                NativeMlsBridge.createDirectGroup(
                                                    conversationId,
                                                    currentUserId,
                                                    currentDeviceId,
                                                    packageHex
                                                )
                                            } ?: error("The native MLS group could not be created.")
                                            if (group.welcomeHex != null) {
                                                val messageId = api.sendMlsMessage(
                                                    conversationId,
                                                    currentUserId,
                                                    currentDeviceId,
                                                    group.welcomeHex
                                                )
                                                api.completeConversationBootstrap(
                                                    conversationId,
                                                    currentDeviceId,
                                                    messageId
                                                )
                                                withContext(Dispatchers.IO) {
                                                    NativeMlsBridge.markWelcomePublished(conversationId)
                                                }
                                            }
                                        } catch (bootstrapError: Exception) {
                                            runCatching {
                                                api.releaseConversationBootstrap(conversationId, currentDeviceId)
                                            }
                                            throw bootstrapError
                                        }
                                    }
                                    onConversationCreated(conversationId)
                                } catch (e: Exception) {
                                    status = e.message ?: "Unable to create secure conversation."
                                }
                            }
                        }
                    ) { Text("Chat") }
                }
            }
        }
    }
}

@Composable
fun SharedMediaScreen(onBrowseLocalFiles: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MessengerPageBackground)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Media & files", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Text("Shared attachments and your local media tools.", color = MessengerPageMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MessengerPageSurface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(66.dp).background(Color(0xFF25213F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▧", color = MessengerPageAccent, fontSize = 30.sp)
                }
                Text("No shared media yet", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Conversation media will appear here after encrypted attachment transfer is enabled. Your existing viewers and file tools remain available below.",
                    color = MessengerPageMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        }
        Text("LOCAL TOOLS", color = MessengerPageAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MessengerPageSurface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("File manager · Image viewer · Video & audio player · Text editor · ZIP tools · Package inspection", color = Color.White, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                Button(onClick = onBrowseLocalFiles, modifier = Modifier.fillMaxWidth()) {
                    Text("Browse local files")
                }
            }
        }
    }
}
