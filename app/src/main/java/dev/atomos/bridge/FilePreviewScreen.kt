package dev.atomos.bridge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

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
            if (ext in imageExtensions) {
                bitmap = withContext(Dispatchers.IO) { decodePreviewBitmap(context, file.uri) }
                if (bitmap == null) error("This image format could not be decoded.")
                return@LaunchedEffect
            }
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

    if (ext in imageExtensions) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            previewError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(previewError ?: "Unable to open image", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            }
            bitmap != null -> ImageViewer(
                file = file,
                bitmap = bitmap!!,
                onBack = onBack
            )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This image format could not be decoded.") }
        }
        return
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

@Composable
private fun ImageViewer(file: DocumentFile, bitmap: Bitmap, onBack: () -> Unit) {
    val context = LocalContext.current
    var zoom by remember(file.uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(file.uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(file.uri) { mutableFloatStateOf(0f) }
    var rotation by remember(file.uri) { mutableFloatStateOf(0f) }
    var controlsVisible by remember(file.uri) { mutableStateOf(true) }
    var detailsVisible by remember(file.uri) { mutableStateOf(false) }

    val transformState = rememberTransformableState { zoomChange, panChange, rotationChange ->
        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
        offsetX += panChange.x
        offsetY += panChange.y
        rotation += rotationChange
    }

    fun resetView() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        rotation = 0f
    }

    fun shareImage() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = file.type?.takeIf { it.startsWith("image/") } ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, file.uri)
            clipData = android.content.ClipData.newUri(context.contentResolver, file.name ?: "image", file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent, "Share image"))
        }
    }

    Box(Modifier.fillMaxSize().padding(0.dp).then(Modifier)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name ?: "Image",
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(file.uri) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = {
                            if (zoom > 1f) {
                                resetView()
                            } else {
                                zoom = 2.5f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        },
                        onLongPress = { detailsVisible = true }
                    )
                }
                .transformable(state = transformState)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offsetX
                    translationY = offsetY
                    rotationZ = rotation
                }
        )

        if (controlsVisible) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Color(0xCC101116), shape = MaterialTheme.shapes.extraLarge) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(file.name ?: "Image", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${(zoom * 100).roundToInt()}% · ${bitmap.width} × ${bitmap.height}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
                Surface(color = Color(0xCC101116), shape = MaterialTheme.shapes.extraLarge) {
                    Row {
                        IconButton(onClick = { detailsVisible = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Image details", tint = Color.White)
                        }
                        IconButton(onClick = { shareImage() }) {
                            Icon(Icons.Default.Share, contentDescription = "Share image", tint = Color.White)
                        }
                    }
                }
            }

            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = Color(0xE6101116),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZOOM", color = Color.LightGray, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                        Slider(
                            value = zoom,
                            onValueChange = { zoom = it },
                            valueRange = 1f..8f,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                        Text("${zoom.toInt()}×", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = Color.White)
                        }
                        TextButton(onClick = { zoom = 1f; offsetX = 0f; offsetY = 0f }) { Text("Fit", color = Color.White) }
                        TextButton(onClick = { zoom = 2f }) { Text("1:1", color = Color.White) }
                        IconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(8f) }) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = Color.White)
                        }
                        IconButton(onClick = { rotation -= 90f }) {
                            Icon(Icons.Default.RotateLeft, contentDescription = "Rotate left", tint = Color.White)
                        }
                        IconButton(onClick = { rotation += 90f }) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate right", tint = Color.White)
                        }
                        IconButton(onClick = { resetView() }) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset view", tint = Color.White)
                        }
                    }
                    Text(
                        "Pinch to zoom · Drag to move · Double tap to zoom · Tap to hide controls",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        if (detailsVisible) {
            AlertDialog(
                onDismissRequest = { detailsVisible = false },
                title = { Text("Image details") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(file.name ?: "Image", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text("Dimensions: ${bitmap.width} × ${bitmap.height} px")
                        Text("File size: ${android.text.format.Formatter.formatShortFileSize(context, file.length())}")
                        Text("Format: ${file.type ?: file.name?.substringAfterLast('.', "Unknown")?.uppercase() ?: "Unknown"}")
                        Text("Zoom: ${(zoom * 100).roundToInt()}%")
                    }
                },
                confirmButton = { TextButton(onClick = { detailsVisible = false }) { Text("Done") } }
            )
        }
    }
}

private fun decodePreviewBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: error("Cannot read image.")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // Decode directly from the provider in two passes, avoiding a second full-size byte-array copy.
    val maxDimension = 3072
    var sampleSize = 1
    while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
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
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}
