package dev.atomos.bridge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.exifinterface.media.ExifInterface
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

private const val MAX_PREVIEW_BYTES = 16 * 1024 * 1024
private const val MAX_TEXT_PREVIEW_BYTES = 8 * 1024 * 1024
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
    var discardDialogVisible by remember(file.uri) { mutableStateOf(false) }
    val safeBack: () -> Unit = {
        if (dirty) discardDialogVisible = true else onBack()
    }
    BackHandler { safeBack() }

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
                    val limit = if (ext in textExtensions) MAX_TEXT_PREVIEW_BYTES else MAX_PREVIEW_BYTES
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit) {
                            "Preview limit is ${limit / (1024 * 1024)} MB. Open larger files with another app."
                        }
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
                onBack = safeBack
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
                    IconButton(onClick = safeBack) {
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
                    HtmlFilePreview(bytes?.toString(Charsets.UTF_8).orEmpty())
                }
                ext in textExtensions -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("Editing locally · UTF-8", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { editorText = it; dirty = true },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        enabled = !saving
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

    if (discardDialogVisible) {
        AlertDialog(
            onDismissRequest = { discardDialogVisible = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your edits to this file have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    discardDialogVisible = false
                    dirty = false
                    onBack()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { discardDialogVisible = false }) { Text("Keep editing") }
            }
        )
    }
}

@Composable
private fun HtmlFilePreview(html: String) {
    val context = LocalContext.current
    val webView = remember(html) {
        WebView(context).apply {
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
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
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
    var viewportSize by remember(file.uri) { mutableStateOf(IntSize.Zero) }

    fun applyZoom(value: Float) {
        zoom = value.coerceIn(1f, 8f)
        val bounded = clampImageTranslation(bitmap, viewportSize, zoom, rotation, offsetX, offsetY)
        offsetX = bounded.x
        offsetY = bounded.y
    }
    fun applyRotation(value: Float) {
        rotation = value
        val bounded = clampImageTranslation(bitmap, viewportSize, zoom, rotation, offsetX, offsetY)
        offsetX = bounded.x
        offsetY = bounded.y
    }

    val transformState = rememberTransformableState { zoomChange, panChange, rotationChange ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 8f)
        val nextRotation = rotation + rotationChange
        zoom = nextZoom
        rotation = nextRotation
        val bounded = clampImageTranslation(
            bitmap, viewportSize, nextZoom, nextRotation,
            offsetX + panChange.x, offsetY + panChange.y
        )
        offsetX = bounded.x
        offsetY = bounded.y
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
                .onSizeChanged { viewportSize = it }
                .pointerInput(file.uri) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = {
                            if (zoom > 1f) {
                                resetView()
                            } else {
                                applyZoom(2.5f)
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
                            onValueChange = { applyZoom(it) },
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
                        IconButton(onClick = { applyZoom((zoom - 0.5f).coerceAtLeast(1f)) }) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = Color.White)
                        }
                        TextButton(onClick = { resetView() }) { Text("Fit", color = Color.White) }
                        TextButton(onClick = { applyZoom(2f) }) { Text("2×", color = Color.White) }
                        IconButton(onClick = { applyZoom((zoom + 0.5f).coerceAtMost(8f)) }) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = Color.White)
                        }
                        IconButton(onClick = { applyRotation(rotation - 90f) }) {
                            Icon(Icons.Default.RotateLeft, contentDescription = "Rotate left", tint = Color.White)
                        }
                        IconButton(onClick = { applyRotation(rotation + 90f) }) {
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
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
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
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    return orientBitmap(decoded, orientation)
}

private fun orientBitmap(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return source
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
        if (it !== source) source.recycle()
    }
}

private fun clampImageTranslation(
    bitmap: Bitmap,
    viewport: IntSize,
    zoom: Float,
    rotation: Float,
    x: Float,
    y: Float
): Offset {
    if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
    val fitScale = minOf(
        viewport.width.toFloat() / bitmap.width.coerceAtLeast(1),
        viewport.height.toFloat() / bitmap.height.coerceAtLeast(1)
    )
    val width = bitmap.width * fitScale * zoom
    val height = bitmap.height * fitScale * zoom
    val radians = Math.toRadians(rotation.toDouble())
    val rotatedWidth = kotlin.math.abs(width * kotlin.math.cos(radians)) +
        kotlin.math.abs(height * kotlin.math.sin(radians))
    val rotatedHeight = kotlin.math.abs(width * kotlin.math.sin(radians)) +
        kotlin.math.abs(height * kotlin.math.cos(radians))
    val maxX = ((rotatedWidth - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((rotatedHeight - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
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
