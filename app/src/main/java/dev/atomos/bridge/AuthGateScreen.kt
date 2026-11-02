package dev.atomos.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
private val AuthSurface = Color(0xFF151A27)

@Composable
fun AuthLoadingScreen() {
    Box(Modifier.fillMaxSize().background(AuthBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("ATOMOS", color = AuthViolet, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            CircularProgressIndicator(color = AuthViolet)
            Text("Checking your secure session…", color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AuthGateScreen(error: String?, launching: Boolean, onSignIn: () -> Unit) {
    Box(Modifier.fillMaxSize().background(AuthBackground).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().background(AuthSurface, RoundedCornerShape(30.dp)).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(Modifier.size(76.dp).background(Color(0xFF25223B), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                Text("A", color = AuthViolet, fontSize = 42.sp, fontWeight = FontWeight.Black)
            }
            Text("ATOMOS", color = AuthViolet, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Text("Your files. Your device. One place.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Sign in with Discord to unlock your Atomos workspace. Your session is stored securely on this device.", color = Color(0xFFADB4C5), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            if (!error.isNullOrBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Button(onClick = onSignIn, enabled = !launching, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = AuthViolet, contentColor = Color(0xFF10111A))) {
                if (launching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF10111A))
                    Spacer(Modifier.width(10.dp))
                    Text("Waiting for Discord…", fontWeight = FontWeight.Bold)
                } else Text("Continue with Discord", fontWeight = FontWeight.Bold)
            }
            Text("Authentication is required before opening files and utilities.", color = Color(0xFF7F899D), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}
