package dev.atomos.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private val Violet = androidx.compose.ui.graphics.Color(0xFF9B8CFF)
private val Background = androidx.compose.ui.graphics.Color(0xFF090B12)
private val SurfaceDark = androidx.compose.ui.graphics.Color(0xFF151A27)

private const val MAX_PACKAGE_FILE_BYTES = 1_000_000_000L

private fun copyUriToCache(context: android.content.Context, uri: Uri, displayName: String, prefix: String): File {
    val extension = displayName.substringAfterLast('.', "").lowercase()
        .filter { it.isLetterOrDigit() }
        .take(12)
    val suffix = if (extension.isBlank()) "" else ".$extension"
    val target = File(context.cacheDir, "$prefix-${UUID.randomUUID()}$suffix")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= MAX_PACKAGE_FILE_BYTES) { "Selected package exceeds the 1 GB size limit." }
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        } ?: error("Unable to read selected file.")
        require(target.isFile && target.length() > 0L) { "Selected file is empty." }
        return target
    } catch (e: Exception) {
        target.delete()
        throw e
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        store.remove("access", "refresh", "oauth_verifier")
        setContent { AtomosApp(store, lifecycleScope) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtomosApp(store: SecureStore, activityScope: CoroutineScope) {
    val context = LocalContext.current
    // Activity-owned work survives temporary Compose screen removal during system pickers and dialogs.
    val monitor = remember { NetworkMonitor(context) }
    var page by remember { mutableStateOf("Files") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var network by remember { mutableStateOf(NetworkSnapshot(false, "Offline", false, 0, 0, "")) }
    var folderUri by remember { mutableStateOf<Uri?>(store.get("folder_uri")?.let(Uri::parse)) }
    var currentDirectory by remember { mutableStateOf<DocumentFile?>(null) }
    var entries by remember { mutableStateOf(listOf<DocumentFile>()) }
    var folderName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<DocumentFile?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var transferSource by remember { mutableStateOf<DocumentFile?>(null) }
    var transferMove by remember { mutableStateOf(false) }
    var speedResult by remember { mutableStateOf("Run a test to measure your connection.") }
    var speedBusy by remember { mutableStateOf(false) }
    var speedProgress by remember { mutableFloatStateOf(0f) }
    fun refreshFiles(dir: DocumentFile?) {
        entries = dir?.listFiles()?.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() })?.toList().orEmpty()
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            folderUri = uri; currentDirectory = DocumentFile.fromTreeUri(context, uri); refreshFiles(currentDirectory); store.put("folder_uri", uri.toString())
        }
    }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val source = transferSource
        if (uri != null && source != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            activityScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val targetDir = DocumentFile.fromTreeUri(context, uri) ?: error("Unable to open destination folder.")
                        val original = source.name ?: "copied-file"
                        var name = original
                        var suffix = 1
                        while (targetDir.findFile(name) != null) {
                            val dot = original.lastIndexOf('.')
                            name = if (dot > 0) "${original.substring(0, dot)} ($suffix)${original.substring(dot)}" else "$original ($suffix)"
                            suffix++
                        }
                        val target = targetDir.createFile(source.type ?: "application/octet-stream", name) ?: error("Could not create the destination file.")
                        try {
                            context.contentResolver.openInputStream(source.uri)?.use { input ->
                                context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) } ?: error("Could not write the destination file.")
                            } ?: error("Could not read the source file.")
                            if (transferMove && !source.delete()) error("File copied, but Android could not remove the original.")
                        } catch (e: Exception) { target.delete(); throw e }
                    }
                    status = if (transferMove) "File moved." else "File copied."
                    refreshFiles(currentDirectory)
                } catch (e: Exception) { status = e.message ?: "File transfer failed." }
                finally { transferSource = null; transferMove = false }
            }
        } else { transferSource = null; transferMove = false }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) activityScope.launch {
            busy = true
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "package.apk"
                    val target = copyUriToCache(context, uri, name, "picked")
                    ArchiveInstaller.prepare(context, target)
                }
                ArchiveInstaller.install(context, prepared.files)
                status = "Installer session started for ${prepared.label}"
            } catch (e: Exception) { status = e.message ?: "Unable to prepare installer." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        var prev = monitor.counters()
        var last = System.currentTimeMillis()
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val current = monitor.counters()
            network = monitor.snapshot(prev.first, prev.second, now - last)
            prev = current; last = now
        }
    }
    LaunchedEffect(folderUri) { currentDirectory = folderUri?.let { DocumentFile.fromTreeUri(context, it) }; refreshFiles(currentDirectory) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Background, surface = SurfaceDark)) {
        Scaffold(
            containerColor = Background,
            topBar = {
                if (page != "Files") CenterAlignedTopAppBar(
                    title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ATOMOS", fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontSize = 16.sp)
                        Text(if (network.connected) "${network.transport} · ↓ ${formatRate(network.downloadBps)} · ↑ ${formatRate(network.uploadBps)}" else "No internet connection", style = MaterialTheme.typography.labelSmall, color = if (network.connected) Violet else MaterialTheme.colorScheme.error)
                    } },
                    actions = { Text(if (network.validated) "●" else "○", color = if (network.validated) Violet else MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 18.dp)) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Background, titleContentColor = MaterialTheme.colorScheme.onBackground)
                )
            },
            bottomBar = {
                if (page != "Files") NavigationBar(containerColor = SurfaceDark) {
                    listOf("Home", "Network", "Files", "Installer", "Settings").forEach { item ->
                        NavigationBarItem(selected = page == item, onClick = { page = item }, icon = { Text(when(item) { "Home" -> "⌂"; "Network" -> "⌁"; "Files" -> "▤"; "Installer" -> "⬇"; else -> "⚙" }) }, label = { Text(item) })
                    }
                }
            }
        ) { padding ->
            if (page == "Files") {
                FileManagerScreen(
                    onNavigate = { page = it },
                    onInstallPackage = { file ->
                        activityScope.launch {
                            busy = true
                            try {
                                val prepared = withContext(Dispatchers.IO) {
                                    val target = copyUriToCache(context, file.uri, file.name ?: "package.apk", "file")
                                    ArchiveInstaller.prepare(context, target)
                                }
                                ArchiveInstaller.install(context, prepared.files)
                                status = "Installer session started for ${prepared.label}"
                            } catch (e: Exception) { status = e.message ?: "Install failed." }
                            finally { busy = false }
                        }
                    }
                )
            } else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                when (page) {
                    "Home" -> HomePage(network, status, onNetwork = { page = "Network" }, onFiles = { page = "Files" }, onInstaller = { page = "Installer" }, onSpeed = { page = "Network" })
                    "Network" -> NetworkPage(network, speedResult, speedBusy, speedProgress) {
                        if (!speedBusy) activityScope.launch {
                            speedBusy = true; speedProgress = 0f
                            try { speedResult = withContext(Dispatchers.IO) { runSpeedTest { speedProgress = it } } }
                            catch (e: Exception) { speedResult = "Test failed: ${e.message}" }
                            finally { speedBusy = false; speedProgress = 1f }
                        }
                    }
                    "Installer" -> InstallerPage(status, busy, { filePicker.launch(arrayOf("*/*")) }, { status = "Supported: APK, APKM, APKS, APKX and XAPK. Split archives are installed as one package session." })
                    "Settings" -> SettingsPage(status, network.ipAddress)
                }
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable private fun HomePage(n: NetworkSnapshot, status: String, onNetwork: () -> Unit, onFiles: () -> Unit, onInstaller: () -> Unit, onSpeed: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
        item { Text("Your device, at a glance.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (n.validated) "INTERNET CONNECTED" else "CHECK CONNECTION", color = Violet, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(n.transport, style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column { Text("DOWNLOAD", style = MaterialTheme.typography.labelSmall); Text(formatRate(n.downloadBps), fontWeight = FontWeight.Bold) }
                    Column { Text("UPLOAD", style = MaterialTheme.typography.labelSmall); Text(formatRate(n.uploadBps), fontWeight = FontWeight.Bold) }
                }
                Text("Live device connectivity and throughput", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        item { UtilityCard("Network monitor", "Live connection, throughput, IP and speed test", "↗", onNetwork) }
        item { UtilityCard("File manager", "Browse folders, organize and inspect files", "▤", onFiles) }
        item { UtilityCard("Package installer", "Install APK and split APK archives", "⬇", onInstaller) }
        item { Text("More utilities will appear here as independent tiles.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun UtilityCard(title: String, detail: String, glyph: String, click: () -> Unit) {
    Card(onClick = click, colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = Violet, fontSize = 25.sp, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("›", color = Violet, fontSize = 24.sp)
        }
    }
}
@Composable private fun NetworkPage(n: NetworkSnapshot, result: String, busy: Boolean, progress: Float, run: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
        item { Text("Network", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (n.validated) "Internet available" else "Internet unavailable", color = if (n.validated) Violet else MaterialTheme.colorScheme.error)
            Text(n.transport, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("↓  ${formatRate(n.downloadBps)}"); Text("↑  ${formatRate(n.uploadBps)}") }
            Text("IP address: ${n.ipAddress.ifBlank { "Unavailable" }}", style = MaterialTheme.typography.bodySmall)
            Text("Live rates are device-wide estimates from Android traffic counters.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { Text("Internet speed test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item { Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(result)
            if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Button(onClick = run, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Testing…" else "Start speed test") }
            Text("Uses Cloudflare's public speed measurement endpoint. Results vary with server distance and network conditions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
    }
}
@Composable private fun FilesPage(root: Uri?, current: DocumentFile?, files: List<DocumentFile>, folderName: String, setFolderName: (String) -> Unit, choose: () -> Unit, create: () -> Unit, open: (DocumentFile) -> Unit, rename: (DocumentFile) -> Unit, delete: (DocumentFile) -> Unit, transfer: (DocumentFile, Boolean) -> Unit, back: () -> Unit, renameTarget: DocumentFile?, renameValue: String, setRename: (String) -> Unit, confirmRename: () -> Unit, cancelRename: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
        item { Text("File manager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = choose, modifier = Modifier.weight(1f)) { Text(if (root == null) "Choose storage" else "Change folder") }; OutlinedButton(onClick = back, enabled = root != null && current?.uri != root) { Text("Up") } } }
        item { Text("Atomos uses Android's folder picker. Only the folder you grant is accessible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(folderName, setFolderName, modifier = Modifier.weight(1f), label = { Text("New folder") }, singleLine = true); Spacer(Modifier.width(8.dp)); Button(onClick = create, enabled = folderName.isNotBlank()) { Text("Create") } } }
        if (files.isEmpty()) item { Text(if (root == null) "Choose a folder to begin." else "This folder is empty.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(files, key = { it.uri.toString() }) { file ->
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open(file) }) {
                        Text(if (file.isDirectory) "▣" else "▤", color = Violet, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) { Text(file.name ?: "Unnamed", fontWeight = FontWeight.Medium); Text(if (file.isDirectory) "Folder" else "${file.type ?: "File"} · ${file.length()} bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("Open", color = Violet)
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        if (!file.isDirectory) {
                            TextButton(onClick = { transfer(file, false) }) { Text("Copy") }
                            TextButton(onClick = { transfer(file, true) }) { Text("Move") }
                        }
                        TextButton(onClick = { rename(file) }) { Text("Rename") }
                        TextButton(onClick = { delete(file) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (renameTarget != null) item {
            OutlinedTextField(renameValue, setRename, label = { Text("Rename to") }, modifier = Modifier.fillMaxWidth())
            Row { TextButton(onClick = cancelRename) { Text("Cancel") }; Button(onClick = confirmRename) { Text("Save name") } }
        }
    }
}
@Composable private fun InstallerPage(status: String, busy: Boolean, pick: () -> Unit, info: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Package installer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Install single APKs or split-package archives in one Android PackageInstaller session.")
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Supported formats", color = Violet, fontWeight = FontWeight.Bold)
                Text("APK · APKM · APKS · APKX · XAPK")
                Text("Split archives are unpacked locally. Atomos does not upload your packages.")
                Button(onClick = pick, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Preparing…" else "Choose package") }
                OutlinedButton(onClick = info, modifier = Modifier.fillMaxWidth()) { Text("Installation details") }
            }
        }
        Text("Android may ask you to allow Atomos to install unknown apps. You remain in control of the final system installation prompt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun SettingsPage(status: String, ip: String) {
    Column(Modifier.fillMaxSize().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device", color = Violet, fontWeight = FontWeight.Bold)
                Text("Local IP: " + ip.ifBlank { "Unavailable" })
                Text("Android 10 and newer · Local-first utility suite")
                Text("No account or sign-in required")
                Text("Atomos 1.0.7")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Activity", color = Violet, fontWeight = FontWeight.Bold)
                Text(status)
            }
        }
    }
}
private fun runSpeedTest(progress: (Float) -> Unit): String {
    fun request(url: String, method: String = "GET", bytes: ByteArray? = null): Pair<Long, Long> {
        val start = System.nanoTime()
        val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 12000; readTimeout = 30000; useCaches = false; setRequestProperty("Cache-Control", "no-cache") }
        try {
            if (bytes != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/octet-stream"); c.outputStream.use { it.write(bytes) } }
            val code = c.responseCode
            if (code !in 200..299) error("Speed-test server returned HTTP $code.")
            val stream = c.inputStream
            var count = 0L
            stream?.use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; count += n } }
            return Pair(count, System.nanoTime() - start)
        } finally { c.disconnect() }
    }
    val base = "https://speed.cloudflare.com"
    val pings = (1..3).map { request("$base/__down?bytes=0").second / 1_000_000.0 }
    progress(0.15f)
    val download = request("$base/__down?bytes=25000000")
    progress(0.65f)
    val upload = request("$base/__up", "POST", ByteArray(5_000_000))
    progress(1f)
    val downMbps = download.first * 8.0 / (download.second / 1e9) / 1_000_000.0
    val upMbps = 5_000_000.0 * 8.0 / (upload.second / 1e9) / 1_000_000.0
    val ping = pings.average()
    val jitter = pings.maxOrNull()!! - pings.minOrNull()!!
    return "Download: ${"%.1f".format(downMbps)} Mbps\nUpload: ${"%.1f".format(upMbps)} Mbps\nLatency: ${"%.0f".format(ping)} ms · Jitter: ${"%.1f".format(jitter)} ms"
}
