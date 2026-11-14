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

@Composable
fun AuthLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CircularProgressIndicator(color = Violet)
            Text("Checking your Atomos session", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Secure sign-in", color = Color(0xFF9299AC), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AuthGateScreen(error: String?, launching: Boolean, onSignIn: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Background).padding(24.dp),
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
                Text("A", color = Violet, fontSize = 38.sp, fontWeight = FontWeight.Black)
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
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
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
                        colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color(0xFF11111B))
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
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Your profile", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("ATOMOS ACCOUNT", color = Violet, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(user.name ?: "Atomos user", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(user.email ?: "Discord account", color = Color(0xFF9299AC), style = MaterialTheme.typography.bodyMedium)
                Text("Account ID: ${user.id}", color = Color(0xFF737B8E), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DEVICE", color = Violet, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("Local IP: ${ip.ifBlank { "Unavailable" }}", color = Color.White)
                Text("Android 10 and newer", color = Color(0xFF9299AC), style = MaterialTheme.typography.bodySmall)
                Text(status, color = Color(0xFF9299AC), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
