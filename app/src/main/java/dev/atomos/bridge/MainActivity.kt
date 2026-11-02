package dev.atomos.bridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Ink = Color(0xFF080A11)
private val Panel = Color(0xFF141927)
private val Violet = Color(0xFF9B8CFF)
private val Muted = Color(0xFF9AA4B9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        val api = AtomosApi()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Panel)) {
                BridgeDashboard(store, api,
                    { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) })
            }
        }
    }
}

@Composable
private fun BridgeDashboard(store: SecureStore, api: AtomosApi, openAccessibility: () -> Unit, openNotifications: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(if (store.get("access") != null) "signed" else "email") }
    var busy by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf(store.get("device_id").orEmpty()) }
    var deviceToken by remember { mutableStateOf(store.get("device_token")) }
    var status by remember { mutableStateOf(if (deviceToken == null) "Not paired" else "Registered device") }
    var message by remember { mutableStateOf("Sign in to register this phone to your account.") }
    var accessibility by remember { mutableStateOf(BridgeState.isAccessibilityConnected()) }

    fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { message = e.message ?: "Request failed." }
            finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ATOMOS / DEVICE BRIDGE", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.8.sp)
                Text("Your phone, connected.", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("A secure, permission-led bridge. No hidden access.", color = Muted, fontSize = 13.sp)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DEVICE CONNECTION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        Text(if (deviceToken != null) "REGISTERED" else "NOT PAIRED",
                            color = if (deviceToken != null) Color(0xFF72E1B6) else Color(0xFFFFC477), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(status, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(message, color = Muted, fontSize = 12.sp)
                    if (deviceId.isNotBlank()) Text("Device ID  $deviceId", color = Color(0xFF7D88A0), fontSize = 10.sp)
                }
            }
            when (stage) {
                "email" -> {
                    Text("ACCOUNT SIGN-IN", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    OutlinedTextField(email, { email = it.trim() }, label = { Text("Email address") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Button(onClick = { task {
                        api.sendOtp(email)
                        stage = "otp"; message = "A one-time verification code was sent to your email."
                    } }, enabled = !busy && email.contains("@"), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Sending…" else "Send sign-in code") }
                }
                "otp" -> {
                    Text("VERIFY YOUR EMAIL", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    OutlinedTextField(otp, { otp = it.filter(Char::isDigit).take(8) }, label = { Text("Email verification code") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = { task {
                        val tokens = api.verifyOtp(email, otp)
                        store.put("access", tokens.accessToken); store.put("refresh", tokens.refreshToken)
                        stage = "signed"; message = "Account verified. You can now register this phone."
                    } }, enabled = !busy && otp.length >= 6, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Verifying…" else "Verify and continue") }
                }
                else -> {
                    Text("SECURE PAIRING", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Button(onClick = { task {
                        var bearer = store.get("access") ?: error("Please sign in again.")
                        val extra = JSONObject().put("device_name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("app_version", "0.2.0")
                        val challenge = try { api.call("create_pairing", bearer, extra = extra) }
                        catch (e: Exception) {
                            if (!(e.message?.contains("unauthorized", true) ?: false)) throw e
                            val refreshed = api.refresh(store.get("refresh") ?: error("Session expired. Sign in again."))
                            store.put("access", refreshed.accessToken); store.put("refresh", refreshed.refreshToken)
                            bearer = refreshed.accessToken
                            api.call("create_pairing", bearer, extra = extra)
                        }
                        val claimed = api.call("claim_pairing", bearer, extra = JSONObject()
                            .put("code", challenge.getString("code"))
                            .put("device_name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
                        deviceId = claimed.getString("device_id")
                        deviceToken = claimed.getString("device_token")
                        store.put("device_id", deviceId); store.put("device_token", deviceToken!!)
                        status = "Phone registered securely"
                        message = "A unique device credential is encrypted with Android Keystore."
                    } }, enabled = !busy && deviceToken == null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Pairing…" else "Register this phone")
                    }
                    Button(onClick = { task {
                        val result = api.call("device_status", device = deviceToken ?: error("Register this phone first."))
                        status = if (result.optBoolean("authenticated")) "Secure connection verified" else "Connection unavailable"
                        message = "The cloud validated this device credential."
                    } }, enabled = !busy && deviceToken != null, modifier = Modifier.fillMaxWidth()) { Text("Test secure connection") }
                    OutlinedButton(onClick = { task {
                        api.call("revoke_device", store.get("access"), extra = JSONObject().put("device_id", deviceId))
                        store.remove("device_token", "device_id")
                        deviceToken = null; deviceId = ""; status = "Access revoked"
                        message = "The cloud credential has been revoked."
                    } }, enabled = !busy && deviceToken != null, modifier = Modifier.fillMaxWidth()) { Text("Revoke this device") }
                    TextButton(onClick = {
                        store.remove("access", "refresh"); stage = "email"
                        message = "Signed out. The registered device remains until revoked."
                    }) { Text("Sign out") }
                }
            }
            HorizontalDivider(color = Color(0xFF252C3C))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Accessibility engine", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(if (accessibility) "Connected in this app process" else "Enable it in Android settings", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { accessibility = BridgeState.isAccessibilityConnected() }) { Text("Refresh") }
            }
            Button(onClick = openAccessibility, modifier = Modifier.fillMaxWidth()) { Text("Accessibility settings") }
            OutlinedButton(onClick = openNotifications, modifier = Modifier.fillMaxWidth()) { Text("Optional notification access") }
            Text("Important: pairing authenticates this phone only. Internet-based remote commands and the MCP transport are not active yet. Screen capture still requires a separate Android MediaProjection consent.", color = Color(0xFF7D88A0), fontSize = 11.sp)
            Text("ATOMOS DEVICE BRIDGE  •  v0.2.0", color = Color(0xFF596277), fontSize = 10.sp, letterSpacing = 1.4.sp)
        }
    }
}
