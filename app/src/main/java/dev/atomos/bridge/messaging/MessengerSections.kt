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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MessengerPageBackground = Color(0xFF090B12)
private val MessengerPageSurface = Color(0xFF151A27)
private val MessengerPageMuted = Color(0xFF9299AC)
private val MessengerPageAccent = Color(0xFF9B8CFF)

@Composable
fun PeopleDiscoveryScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MessengerPageBackground)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("People", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Text("Find people by username and manage connection requests.", color = MessengerPageMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MessengerPageSurface)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("IDENTITY SETUP REQUIRED", color = MessengerPageAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("People discovery isn't connected yet", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Atomos will use authenticated profiles, unique usernames, contact requests and blocking controls. No address book is uploaded or scanned.",
                    color = MessengerPageMuted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Your contacts stay on your device until you explicitly choose to connect with someone.",
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            color = MessengerPageMuted,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
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
