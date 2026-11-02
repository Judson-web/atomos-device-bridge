package dev.atomos.bridge
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(file: DocumentFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val name = file.name ?: "File"
    val ext = name.substringAfterLast('.', "").lowercase()
    var bytes by remember(file.uri) { mutableStateOf<ByteArray?>(null) }
    var previewError by remember(file.uri) { mutableStateOf<String?>(null) }
    var loading by remember(file.uri) { mutableStateOf(true) }
    var editorText by remember(file.uri) { mutableStateOf("") }
    var dirty by remember(file.uri) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var bitmap by remember(file.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var zipEntries by remember(file.uri) { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    LaunchedEffect(file.uri) {
        if (ext in setOf("mp3","m4a","aac","ogg","wav","flac","opus","mp4","mkv","mov","webm","3gp","m4v")) { loading = false; return@LaunchedEffect }
        try {
            val data = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    val out = ByteArrayOutputStream(); val buf = ByteArray(32768); var total = 0
                    while (true) { val n = input.read(buf); if (n < 0) break; total += n; require(total <= 32 * 1024 * 1024) { "Preview limit is 32 MB." }; out.write(buf, 0, n) }
                    out.toByteArray()
                } ?: error("Cannot read file.")
            }
            bytes = data
            if (ext in setOf("txt","md","log","json","xml","csv","kt","java","js","ts","css","yaml","yml","properties","gradle","sh","py")) editorText = data.toString(Charsets.UTF_8)
            if (ext in setOf("jpg","jpeg","png","webp","gif","bmp","heic","avif")) bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(data, 0, data.size) }
            if (ext == "zip") zipEntries = withContext(Dispatchers.IO) {
                val list = mutableListOf<Pair<String,Long>>()
                ZipInputStream(data.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; list += e.name to e.size; z.closeEntry(); require(list.size <= 10000) { "Archive has too many entries." } } }
                list
            }
        } catch (e: Exception) { previewError = e.message ?: "Cannot preview file." } finally { loading = false }
    }
    val viewerLabel = when {
        ext == "zip" -> "ZIP contents"
        ext in setOf("html","htm") -> "HTML viewer"
        ext in setOf("mp3","m4a","aac","ogg","wav","flac","opus") -> "Music player"
        ext in setOf("mp4","mkv","mov","webm","3gp","m4v") -> "Video player"
        ext in setOf("jpg","jpeg","png","webp","gif","bmp","heic","avif") -> "Image viewer"
        else -> "Text editor"
    }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text(name, maxLines = 1); Text(viewerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
        actions = { if (ext in setOf("txt","md","log","json","xml","csv","kt","java","js","ts","css","yaml","yml","properties","gradle","sh","py")) IconButton(enabled = dirty && !saving, onClick = { saving = true }) { Icon(Icons.Default.Save, contentDescription = "Save") } }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                previewError != null -> Text(previewError ?: "Error", Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                ext == "zip" -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item { Text("${zipEntries.size} entries", style = MaterialTheme.typography.titleMedium) }
                    items(zipEntries) { (entry,size) -> ListItem(headlineContent = { Text(entry) }, supportingContent = { Text(if (size < 0) "Folder" else android.text.format.Formatter.formatShortFileSize(context,size)) }) }
                }
                ext in setOf("jpg","jpeg","png","webp","gif","bmp","heic","avif") -> bitmap?.let { Image(it.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize().padding(8.dp)) } ?: Text("Unsupported image", Modifier.align(Alignment.Center))
                ext in setOf("mp3","m4a","aac","ogg","wav","flac","opus","mp4","mkv","mov","webm","3gp","m4v") -> {
                    val player = remember(file.uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(file.uri)); prepare() } }
                    DisposableEffect(player) { onDispose { player.release() } }
                    AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                }
                ext in setOf("html","htm") -> {
                    val html = bytes?.toString(Charsets.UTF_8).orEmpty()
                    AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; webViewClient = WebViewClient(); loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) } }, modifier = Modifier.fillMaxSize())
                }
                else -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("Editing locally · UTF-8", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(editorText, { editorText = it; dirty = true }, modifier = Modifier.weight(1f).fillMaxWidth())
                    if (dirty) Button(onClick = { saving = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
                }
            }
        }
    }
    LaunchedEffect(saving) {
        if (saving) try {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(editorText.toByteArray(Charsets.UTF_8)) } ?: error("Cannot save file.") }
            dirty = false
        } catch (e: Exception) { previewError = e.message ?: "Save failed." } finally { saving = false }
    }
}
