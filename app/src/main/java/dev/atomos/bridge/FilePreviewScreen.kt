package dev.atomos.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebSettings
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

private const val MAX_PREVIEW_BYTES = 32 * 1024 * 1024
private const val MAX_ZIP_PREVIEW_ENTRIES = 10_000
private const val MAX_ZIP_PREVIEW_EXPANDED_BYTES = 64L * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(file: DocumentFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val name = file.name ?: "File"
    val ext = name.substringAfterLast('.', "").lowercase()
    val textExtensions = setOf("txt", "md", "log", "json", "xml", "csv", "kt", "java", "js", "ts", "css", "yaml", "yml", "properties", "gradle", "sh", "py")
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")
    val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")
    val videoExtensions = setOf("mp4", "mkv", "mov", "webm", "3gp", "m4v")
    val mediaFile = ext in audioExtensions || ext in videoExtensions

    var bytes by remember(file.uri) { mutableStateOf<ByteArray?>(null) }
    var previewError by remember(file.uri) { mutableStateOf<String?>(null) }
    var loading by remember(file.uri) { mutableStateOf(true) }
    var editorText by remember(file.uri) { mutableStateOf("") }
    var dirty by remember(file.uri) { mutableStateOf(false) }
    var saving by remember(file.uri) { mutableStateOf(false) }
    var bitmap by remember(file.uri) { mutableStateOf<Bitmap?>(null) }
    var zipEntries by remember(file.uri) { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    LaunchedEffect(file.uri) {
        if (mediaFile) {
            loading = false
            return@LaunchedEffect
        }
        try {
            val data = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PREVIEW_BYTES) { "Preview limit is 32 MB. Open larger files with another app." }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: error("Cannot read file.")
            }
            bytes = data
            when {
                ext in textExtensions -> editorText = data.toString(Charsets.UTF_8)
                ext in imageExtensions -> bitmap = withContext(Dispatchers.IO) { decodePreviewBitmap(data) }
                ext == "zip" -> zipEntries = withContext(Dispatchers.IO) {
                    val list = mutableListOf<Pair<String, Long>>()
                    var expandedBytes = 0L
                    ZipInputStream(data.inputStream()).use { zip ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            require(list.size < MAX_ZIP_PREVIEW_ENTRIES) { "Archive contains too many entries to preview." }
                            list += entry.name.take(1024) to entry.size
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expandedBytes += count
                                require(expandedBytes <= MAX_ZIP_PREVIEW_EXPANDED_BYTES) { "Archive expands beyond the 64 MB preview limit." }
                            }
                            zip.closeEntry()
                        }
                    }
                    list
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previewError = e.message ?: "Cannot preview file."
        } finally {
            loading = false
        }
    }

    val viewerLabel = when {
        ext == "zip" -> "ZIP contents"
        ext in setOf("html", "htm") -> "HTML viewer · offline"
        ext in audioExtensions -> "Music player"
        ext in videoExtensions -> "Video player"
        ext in imageExtensions -> "Image viewer"
        else -> "Text editor"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1)
                        Text(viewerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ext in textExtensions) {
                        IconButton(enabled = dirty && !saving, onClick = { saving = true }) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                previewError != null -> Text(
                    previewError ?: "Error",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error
                )
                ext == "zip" -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item { Text("${zipEntries.size} entries", style = MaterialTheme.typography.titleMedium) }
                    items(zipEntries) { (entry, size) ->
                        ListItem(
                            headlineContent = { Text(entry, maxLines = 2) },
                            supportingContent = {
                                Text(
                                    if (size < 0) "Size unavailable"
                                    else android.text.format.Formatter.formatShortFileSize(context, size)
                                )
                            }
                        )
                    }
                }
                ext in imageExtensions -> bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize().padding(8.dp))
                } ?: Text("This image format could not be decoded.", Modifier.align(Alignment.Center))
                mediaFile -> {
                    val player = remember(file.uri) {
                        ExoPlayer.Builder(context).build().apply {
                            setMediaItem(MediaItem.fromUri(file.uri))
                            prepare()
                        }
                    }
                    DisposableEffect(player) {
                        onDispose { player.release() }
                    }
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ext == "html" || ext == "htm" -> {
                    val html = bytes?.toString(Charsets.UTF_8).orEmpty()
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = false
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.domStorageEnabled = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.blockNetworkLoads = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.setSupportMultipleWindows(false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: android.webkit.WebResourceRequest
                                    ): Boolean = true
                                }
                                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ext in textExtensions -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("Editing locally · UTF-8", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { editorText = it; dirty = true },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    if (dirty) {
                        Button(
                            onClick = { saving = true },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (saving) "Saving…" else "Save changes") }
                    }
                }
                else -> Text("No built-in preview is available for this file.", Modifier.align(Alignment.Center))
            }
        }
    }

    LaunchedEffect(saving, file.uri) {
        if (saving) {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                        it.write(editorText.toByteArray(Charsets.UTF_8))
                        it.flush()
                    } ?: error("Cannot save file.")
                }
                dirty = false
                previewError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewError = e.message ?: "Save failed."
            } finally {
                saving = false
            }
        }
    }
}

private fun decodePreviewBitmap(data: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val maxDimension = 2048
    var sampleSize = 1
    while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}
