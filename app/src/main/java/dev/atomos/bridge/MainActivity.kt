package dev.atomos.bridge

import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    private var oauthCallback by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oauthCallback = intent?.data?.takeIf { it.scheme == "dev.atomos.bridge" && it.host == "auth-callback" }
        val store = SecureStore(this)
        val api = AtomosApi()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Panel)) {
                BridgeDashboard(store, api, oauthCallback,
                    { uri -> oauthCallback = null; startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) },
                    { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        oauthCallback = intent.data?.takeIf { it.scheme == "dev.atomos.bridge" && it.host == "auth-callback" }
    }
}

@Composable
private fun BridgeDashboard(
    store: SecureStore,
    api: AtomosApi,
    oauthCallback: Uri?,
    openOAuth: (String) -> Unit,
    openAccessibility: () -> Unit,
    openNotifications: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(if (store.get("access") != null) "signed" else "github") }
    var busy by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf(store.get("device_id").orEmpty()) }
    var deviceToken by remember { mutableStateOf(store.get("device_token")) }
    var status by remember { mutableStateOf(if (deviceToken == null) "Not paired" else "Registered device") }
    var message by remember { mutableStateOf("Sign in securely with your GitHub account to register this phone.") }
    var accessibility by remember { mutableStateOf(BridgeState.isAccessibilityConnected()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(oauthCallback) {
        val callback = oauthCallback ?: return@LaunchedEffect
        val error = callback.getQueryParameter("error_description") ?: callback.getQueryParameter("error")
        val code = callback.getQueryParameter("code")
        val verifier = store.get("github_pkce_verifier")
        try {
            if (!error.isNullOrBlank()) throw IllegalStateException(error.take(240))
            require(!code.isNullOrBlank() && !verifier.isNullOrBlank()) { "GitHub sign-in could not be completed. Please try again." }
            val tokens = withContext(Dispatchers.IO) { api.exchangeGitHubCode(code, verifier) }
            store.put("access", tokens.accessToken)
            store.put("refresh", tokens.refreshToken)
            store.remove("github_pkce_verifier")
            stage = "signed"
            message = "GitHub account verified. You can now register this phone."
        } catch (e: Exception) {
            store.remove("github_pkce_verifier")
            stage = "github"
            message = e.message ?: "GitHub sign-in failed. Please try again."
        }
    }

    LaunchedEffect(deviceToken) {
        val token = deviceToken ?: return@LaunchedEffect
        status = "Checking device connection…"
        try {
            val result = withContext(Dispatchers.IO) { api.call("device_status", device = token) }
            status = if (result.optBoolean("authenticated")) "Secure connection verified" else "Device needs attention"
            message = "Cloud verification completed."
        } catch (e: ApiException) {
            if (e.status == 401) {
                store.remove("device_token", "device_id")
                deviceToken = null
                deviceId = ""
                status = "Device registration expired or revoked"
                message = "Register this phone again to restore its device credential."
            } else {
                status = "Temporarily offline"
                message = e.message ?: "Could not verify the device right now."
            }
        } catch (e: Exception) {
            status = "Temporarily offline"
            message = e.message ?: "Could not verify the device right now."
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessibility = BridgeState.isAccessibilityConnected()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: Exception) {
                message = e.message ?: "Request failed."
                if (message.contains("sign in again", ignoreCase = true)) stage = "github"
            }
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
                "github" -> {
                    Text("GITHUB AUTHENTICATION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Button(onClick = {
                        if (!busy) {
                            try {
                                val verifier = api.newPkceVerifier()
                                store.put("github_pkce_verifier", verifier)
                                openOAuth(api.githubAuthorizationUrl(verifier))
                                message = "Complete authorization in your browser. Atomos will resume automatically."
                            } catch (e: Exception) {
                                store.remove("github_pkce_verifier")
                                message = e.message ?: "Could not start GitHub sign-in."
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Please wait…" else "Continue with GitHub")
                    }
                    Text("GitHub is the only sign-in provider in this app. Authorization uses the system browser and a PKCE-protected callback.", color = Muted, fontSize = 11.sp)
                }
                "signed" -> {
                    Text("SECURE PAIRING", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Button(onClick = { task {
                        val extra = JSONObject().put("device_name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("app_version", "0.2.2")
                        val challenge = withContext(Dispatchers.IO) {
                            api.callAsUser("create_pairing", store, extra)
                        }
                        val claimed = withContext(Dispatchers.IO) {
                            api.callAsUser("claim_pairing", store, JSONObject()
                                .put("code", challenge.getString("code"))
                                .put("device_name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
                        }
                        deviceId = claimed.getString("device_id")
                        deviceToken = claimed.getString("device_token")
                        store.put("device_id", deviceId); store.put("device_token", deviceToken!!)
                        status = "Phone registered securely"
                        message = "A unique device credential is encrypted with Android Keystore."
                    } }, enabled = !busy && deviceToken == null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Pairing…" else "Register this phone")
                    }
                    Button(onClick = { task {
                        val result = withContext(Dispatchers.IO) {
                            api.call("device_status", device = deviceToken ?: error("Register this phone first."))
                        }
                        status = if (result.optBoolean("authenticated")) "Secure connection verified" else "Connection unavailable"
                        message = "The cloud validated this device credential."
                    } }, enabled = !busy && deviceToken != null, modifier = Modifier.fillMaxWidth()) { Text("Test secure connection") }
                    OutlinedButton(onClick = { task {
                        withContext(Dispatchers.IO) {
                            api.callAsUser("revoke_device", store, JSONObject().put("device_id", deviceId))
                        }
                        store.remove("device_token", "device_id")
                        deviceToken = null; deviceId = ""; status = "Access revoked"
                        message = "The cloud credential has been revoked."
                    } }, enabled = !busy && deviceToken != null, modifier = Modifier.fillMaxWidth()) { Text("Revoke this device") }
                    TextButton(onClick = {
                        store.remove("access", "refresh", "github_pkce_verifier"); stage = "github"
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
            Text("ATOMOS DEVICE BRIDGE  •  v0.2.2", color = Color(0xFF596277), fontSize = 10.sp, letterSpacing = 1.4.sp)
        }
    }
}
