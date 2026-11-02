package dev.atomos.bridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF090B12)
private val Panel = Color(0xFF151927)
private val Violet = Color(0xFF9385FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Panel)) {
                Dashboard(
                    accessibilityReady = BridgeState.isAccessibilityConnected(),
                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onNotification = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
                    onRefresh = { recreate() }
                )
            }
        }
    }
}

@Composable
private fun Dashboard(accessibilityReady: Boolean, onAccessibility: () -> Unit,
                      onNotification: () -> Unit, onRefresh: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ATOMOS", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("Device Bridge", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Your device. Your permissions. Your agent.", color = Color(0xFF9AA3B8), fontSize = 14.sp)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DEVICE STATUS", color = Color(0xFF9AA3B8), fontSize = 11.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(color = if (accessibilityReady) Color(0xFF123D32) else Color(0xFF3B2B18), shape = RoundedCornerShape(50)) {
                            Text(if (accessibilityReady) "READY" else "SETUP REQUIRED", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (accessibilityReady) Color(0xFF6CE0B2) else Color(0xFFFFC477), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(if (accessibilityReady) "Accessibility engine connected" else "Connect the accessibility engine", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("This permission enables visible UI inspection, taps, swipes, navigation and text entry when you authorize an action.", color = Color(0xFFADB5C7), fontSize = 13.sp)
                    Button(onClick = onAccessibility, modifier = Modifier.fillMaxWidth()) { Text("Open accessibility settings") }
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh status") }
                }
            }
            Text("CAPABILITIES", color = Color(0xFF9AA3B8), fontSize = 11.sp, letterSpacing = 2.sp)
            Capability("Screen understanding", "Accessibility UI tree", "Available after enabling service")
            Capability("Touch control", "Tap, swipe, back, home", "Available after enabling service")
            Capability("Notifications", "Optional notification listener", "Requires separate opt-in")
            Capability("Live screen capture", "Android MediaProjection", "Separate system consent, next phase")
            Capability("Remote connection", "Pairing + encrypted bridge", "Not enabled in this starter")
            OutlinedButton(onClick = onNotification, modifier = Modifier.fillMaxWidth()) { Text("Configure notification access") }
            Text("Security: remote commands are not active yet. The bridge will require explicit pairing, a revocable device key, encrypted transport and per-action policy checks.", color = Color(0xFF7F899F), fontSize = 12.sp)
            Text("ATOMOS DEVICE BRIDGE  •  v0.1.0", color = Color(0xFF596277), fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun Capability(title: String, detail: String, status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Violet, fontSize = 12.sp)
            Text(status, color = Color(0xFF9AA3B8), fontSize = 11.sp)
        }
    }
}
