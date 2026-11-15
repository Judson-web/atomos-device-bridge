package dev.atomos.bridge

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AuthViolet = Color(0xFF9B8CFF)
private val AuthBackground = Color(0xFF090B12)
private val AuthSurfaceDark = Color(0xFF151A27)

@Composable
fun AuthLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(AuthBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CircularProgressIndicator(color = AuthViolet)
            Text("Checking your Atomos session", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Secure sign-in", color = Color(0xFF9299AC), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AuthGateScreen(error: String?, launching: Boolean, onSignIn: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(AuthBackground).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                modifier = Modifier.size(78.dp).background(Color(0xFF25213F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = AuthViolet, fontSize = 38.sp, fontWeight = FontWeight.Black)
            }
            Text("ATOMOS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Text(
                "Your conversations. Your devices. Your space.",
                color = Color(0xFF9299AC),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AuthSurfaceDark)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Sign in to Atomos", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Use your Discord account to create your Atomos identity and sync conversations across your devices.",
                        color = Color(0xFF9299AC),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp
                    )
                    if (!error.isNullOrBlank()) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = onSignIn,
                        enabled = !launching,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AuthViolet, contentColor = Color(0xFF11111B))
                    ) {
                        Text(if (launching) "Opening Discord…" else "Continue with Discord", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Sign-in opens Discord in your browser. Atomos never asks for your Discord password.",
                        color = Color(0xFF737B8E),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(user: AuthUser, ip: String, status: String, onSignOut: () -> Unit) {
    val displayName = user.name?.takeIf { it.isNotBlank() } ?: "Atomos user"
    Column(
        modifier = Modifier.fillMaxSize().background(AuthBackground)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(88.dp).background(Color(0xFF292442), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(displayName.take(1).uppercase(), color = AuthViolet, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        }
        Text(displayName, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(user.email ?: "Connected Discord account", color = Color(0xFF9299AC), style = MaterialTheme.typography.bodyMedium)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AuthSurfaceDark)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ACCOUNT", color = AuthViolet, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Connected with Discord", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Account ID: ${user.id}", color = Color(0xFF737B8E), style = MaterialTheme.typography.labelSmall)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AuthSurfaceDark)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("THIS DEVICE", color = AuthViolet, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("Android device", color = Color.White)
                Text("Local network: ${ip.ifBlank { "Unavailable" }}", color = Color(0xFF9299AC), style = MaterialTheme.typography.bodySmall)
                Text(status, color = Color(0xFF9299AC), style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}
