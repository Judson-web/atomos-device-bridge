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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * Honest first-run state for the messaging area.
 *
 * This screen intentionally does not create fake conversations or expose a send action.
 * It remains unavailable until a reviewed protocol engine, device enrollment, and an
 * authenticated ciphertext transport have been integrated.
 */
@Composable
fun MessagingInboxScreen(modifier: Modifier = Modifier) {
    val nativeProtocolVersion = androidx.compose.runtime.remember {
        NativeMlsBridge.protocolVersionOrNull()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090B12))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = "Messages",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Private conversations, thoughtfully built.",
                style = MaterialTheme.typography.bodyMedium,
                color = MessengerMuted
            )
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
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF25213F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⌑", color = MessengerViolet, fontSize = 24.sp)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Security setup",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Surface(
                            color = Color(0xFF3A3020),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                "IN PROGRESS",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color(0xFFE9C98C),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            )
                        }
                    }
                    Text(
                        "The encryption protocol, device identity and secure message transport are being integrated.",
                        color = MessengerMuted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .background(Color(0xFF17182A), CircleShape)
                    .border(1.dp, Color(0xFF343052), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✉", color = MessengerViolet, fontSize = 36.sp)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Your inbox starts here",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No conversations yet. Messaging will become available after secure device enrollment and end-to-end encryption are ready.",
                modifier = Modifier.fillMaxWidth(0.88f),
                color = MessengerMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }


        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111521))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "NATIVE PROTOCOL RUNTIME",
                    color = MessengerViolet,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                Text(
                    nativeProtocolVersion ?: "Native library not present in this build",
                    color = Color(0xFFD8D9E2),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (nativeProtocolVersion == null)
                        "The secure messaging engine is unavailable. No messages can be sent."
                    else
                        "Runtime detected only. Key management and message encryption are not enabled yet.",
                    color = MessengerMuted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111521))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "WHAT'S BEING BUILT",
                    color = MessengerViolet,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                SetupLine("01", "Private one-to-one and group chats")
                SetupLine("02", "Encrypted photos, videos and files")
                SetupLine("03", "Verified devices and visible key changes")
            }
        }
    }
}

@Composable
private fun SetupLine(number: String, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(number, color = MessengerViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFD8D9E2), style = MaterialTheme.typography.bodySmall)
    }
}
