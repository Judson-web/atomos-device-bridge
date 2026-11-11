package dev.atomos.bridge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.app.ActivityManager
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
import androidx.compose.material.icons.filled.MoreVert
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
import kotlinx.coroutines.launch
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
    val imageExtensions = setOf("jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp", "wbmp", "heic", "heif", "avif", "tif", "tiff", "dng", "svg")
    // Trust the provider MIME type as well as the filename. SAF providers often use
    // vendor-specific extensions or omit a conventional extension entirely.
    val isImageFile = file.type?.substringBefore(';')?.trim()?.startsWith("image/", ignoreCase = true) == true ||
        ext in imageExtensions
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
    var animatedDrawable by remember(file.uri) { mutableStateOf<Drawable?>(null) }
    var zipEntries by remember(file.uri) { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var discardDialogVisible by remember(file.uri) { mutableStateOf(false) }
    val safeBack: () -> Unit = {
        if (dirty) discardDialogVisible = true else onBack()
    }
    BackHandler { safeBack() }

    DisposableEffect(animatedDrawable) {
        onDispose { (animatedDrawable as? AnimatedImageDrawable)?.stop() }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap?.let { if (!it.isRecycled) it.recycle() } }
    }

    LaunchedEffect(file.uri) {
        if (mediaFile) {
            loading = false
            return@LaunchedEffect
        }
        try {
            if (isImageFile) {
                val isSvg = ext == "svg" || file.type?.substringBefore(';')?.equals("image/svg+xml", ignoreCase = true) == true
                val isAnimated = ext in setOf("gif", "webp") || file.type?.substringBefore(';')?.lowercase() in setOf("image/gif", "image/webp")
                if (isSvg) {
                    bitmap = withContext(Dispatchers.IO) { decodeSvgBitmap(context, file.uri) }
                    if (bitmap == null) error("This SVG could not be rendered.")
                } else if (isAnimated && android.os.Build.VERSION.SDK_INT >= 28) {
                    animatedDrawable = withContext(Dispatchers.IO) { decodeAnimatedImage(context, file.uri) }
                    if (animatedDrawable == null) error("This animated image could not be decoded.")
                } else {
                    bitmap = withContext(Dispatchers.IO) { decodePreviewBitmap(context, file.uri) }
                    if (bitmap == null) error("This image format could not be decoded.")
                }
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
        } catch (e: OutOfMemoryError) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            bitmap = null
            animatedDrawable = null
            previewError = "This image is too large or complex to decode safely on this device. Try a smaller copy."
            android.util.Log.e("AtomosImage", "Image decode exhausted app memory for $uri", e)
        } catch (e: Exception) {
            previewError = e.message ?: "Cannot preview file."
            android.util.Log.e("AtomosImage", "Image preview failed for $uri", e)
        } finally {
            loading = false
        }
    }

    val viewerLabel = when {
        ext == "zip" -> "ZIP contents"
        ext in setOf("html", "htm") -> "HTML viewer · offline"
        ext in audioExtensions -> "Music player"
        ext in videoExtensions -> "Video player"
        isImageFile -> "Image viewer"
        else -> "Text editor"
    }

    if (isImageFile) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            previewError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(previewError ?: "Unable to open image", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            }
            animatedDrawable != null -> AnimatedImageViewer(file = file, drawable = animatedDrawable!!, onBack = safeBack)
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
    val scope = rememberCoroutineScope()
    var zoom by remember(file.uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(file.uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(file.uri) { mutableFloatStateOf(0f) }
    var rotation by remember(file.uri) { mutableFloatStateOf(0f) }
    var controlsVisible by remember(file.uri) { mutableStateOf(true) }
    var menuExpanded by remember(file.uri) { mutableStateOf(false) }
    var metadataVisible by remember(file.uri) { mutableStateOf(false) }
    var metadataLoading by remember(file.uri) { mutableStateOf(false) }
    var metadata by remember(file.uri) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var metadataError by remember(file.uri) { mutableStateOf<String?>(null) }
    var viewportSize by remember(file.uri) { mutableStateOf(IntSize.Zero) }

    fun applyZoom(value: Float) {
        zoom = value.coerceIn(1f, 12f)
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
        runCatching { context.startActivity(Intent.createChooser(shareIntent, "Share image")) }
    }
    fun loadMetadata() {
        metadataVisible = true
        if (metadata.isNotEmpty() || metadataLoading) return
        metadataLoading = true
        metadataError = null
        scope.launch {
            try {
                metadata = withContext(Dispatchers.IO) {
                    val values = linkedMapOf<String, String>()
                    values["File name"] = file.name ?: "Unknown"
                    values["Format / MIME"] = file.type ?: "Unknown"
                    values["File size"] = android.text.format.Formatter.formatFileSize(context, file.length())
                    values["Last modified"] = if (file.lastModified() > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified())) else "Unknown"
                    values["Image dimensions"] = "${bitmap.width} × ${bitmap.height} px"
                    values["Color config"] = bitmap.config?.name ?: "Unknown"
                    values["Alpha channel"] = if (bitmap.hasAlpha()) "Yes" else "No"
                    runCatching {
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            val exif = ExifInterface(input)
                            fun add(label: String, tag: String) {
                                exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { values[label] = it }
                            }
                            add("Camera maker", ExifInterface.TAG_MAKE)
                            add("Camera model", ExifInterface.TAG_MODEL)
                            add("Lens model", ExifInterface.TAG_LENS_MODEL)
                            add("Date taken", ExifInterface.TAG_DATETIME_ORIGINAL)
                            add("Exposure time", ExifInterface.TAG_EXPOSURE_TIME)
                            add("F-number", ExifInterface.TAG_F_NUMBER)
                            add("ISO", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                            add("Focal length", ExifInterface.TAG_FOCAL_LENGTH)
                            add("Flash", ExifInterface.TAG_FLASH)
                            add("White balance", ExifInterface.TAG_WHITE_BALANCE)
                            add("Orientation", ExifInterface.TAG_ORIENTATION)
                            add("Software", ExifInterface.TAG_SOFTWARE)
                            add("Image description", ExifInterface.TAG_IMAGE_DESCRIPTION)
                            val latLong = exif.latLong
                            if (latLong != null) values["GPS coordinates"] = "${latLong[0]}, ${latLong[1]}"
                            exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)?.let { values["GPS altitude"] = it }
                        } ?: error("The image provider did not allow metadata access.")
                    }.onFailure { values["Metadata note"] = it.message ?: "Some metadata could not be read." }
                    values.toList()
                }
            } catch (e: Exception) {
                metadataError = e.message ?: "Unable to read image metadata."
            } finally {
                metadataLoading = false
            }
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, rotationChange ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 12f)
        val nextRotation = rotation + rotationChange
        zoom = nextZoom
        rotation = nextRotation
        val bounded = clampImageTranslation(bitmap, viewportSize, nextZoom, nextRotation,
            offsetX + panChange.x, offsetY + panChange.y)
        offsetX = bounded.x
        offsetY = bounded.y
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name ?: "Image",
            modifier = Modifier.fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .pointerInput(file.uri) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { if (zoom > 1f) resetView() else applyZoom(2.5f) },
                        onLongPress = { loadMetadata() }
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
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Color(0xCC101116), shape = MaterialTheme.shapes.extraLarge) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(file.name ?: "Image", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${(zoom * 100).roundToInt()}% · ${bitmap.width} × ${bitmap.height}",
                        color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
                Surface(color = Color(0xCC101116), shape = MaterialTheme.shapes.extraLarge) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Image options", tint = Color.White)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("View metadata") }, onClick = { menuExpanded = false; loadMetadata() })
                            DropdownMenuItem(text = { Text("Share image") }, onClick = { menuExpanded = false; shareImage() })
                            DropdownMenuItem(text = { Text("Rotate left") }, onClick = { menuExpanded = false; applyRotation(rotation - 90f) })
                            DropdownMenuItem(text = { Text("Rotate right") }, onClick = { menuExpanded = false; applyRotation(rotation + 90f) })
                            DropdownMenuItem(text = { Text("Reset view") }, onClick = { menuExpanded = false; resetView() })
                        }
                    }
                }
            }

            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                color = Color(0xE6101116), shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZOOM", color = Color.LightGray, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                        Slider(value = zoom, onValueChange = { applyZoom(it) }, valueRange = 1f..12f,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text("${zoom.toInt()}×", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { applyZoom(zoom - 0.5f) }) { Icon(Icons.Default.ZoomOut, "Zoom out", tint = Color.White) }
                        TextButton(onClick = { resetView() }) { Text("Fit", color = Color.White) }
                        TextButton(onClick = { applyZoom(2f) }) { Text("2×", color = Color.White) }
                        IconButton(onClick = { applyZoom(zoom + 0.5f) }) { Icon(Icons.Default.ZoomIn, "Zoom in", tint = Color.White) }
                        IconButton(onClick = { applyRotation(rotation - 90f) }) { Icon(Icons.Default.RotateLeft, "Rotate left", tint = Color.White) }
                        IconButton(onClick = { applyRotation(rotation + 90f) }) { Icon(Icons.Default.RotateRight, "Rotate right", tint = Color.White) }
                        IconButton(onClick = { loadMetadata() }) { Icon(Icons.Default.Info, "View metadata", tint = Color.White) }
                    }
                    Text("Pinch to zoom · Drag to move · Double tap to zoom · Long press for metadata",
                        color = Color.LightGray, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }

        if (metadataVisible) {
            AlertDialog(
                onDismissRequest = { metadataVisible = false },
                title = { Text("Image metadata") },
                text = {
                    when {
                        metadataLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        metadataError != null -> Text(metadataError ?: "Unable to read metadata.")
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 440.dp)) {
                            items(metadata) { (label, value) ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(value, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (metadata.isEmpty()) item { Text("No embedded EXIF metadata was found.") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { metadataVisible = false }) { Text("Close") } }
            )
        }
    }
}

private const val MAX_SVG_SOURCE_BYTES = 16L * 1024L * 1024L
private const val MAX_SVG_DIMENSION = 8192
private const val MIN_IMAGE_DECODE_PIXELS = 1L * 1024L * 1024L
private const val MAX_IMAGE_DECODE_PIXELS = 4L * 1024L * 1024L
private const val MAX_ANIMATED_DECODE_PIXELS = 1L * 1024L * 1024L

/**
 * Keep the decoded preview comfortably below the app heap limit. The previous
 * 16 MP ceiling could require 64 MB for a single ARGB bitmap before Compose,
 * decoder scratch buffers, and animation frames were accounted for.
 */
private fun imageDecodePixelBudget(context: android.content.Context, animated: Boolean = false): Long {
    val memoryClassMb = runCatching {
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
    }.getOrDefault(128)
    val heapFraction = if (animated) 128L else 64L
    val deviceBudget = memoryClassMb.toLong() * 1024L * 1024L / heapFraction / 4L
    val ceiling = if (animated) MAX_ANIMATED_DECODE_PIXELS else MAX_IMAGE_DECODE_PIXELS
    val floor = if (animated) 256L * 1024L else MIN_IMAGE_DECODE_PIXELS
    return deviceBudget.coerceIn(floor, ceiling)
}

private fun decodeSvgBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_SVG_SOURCE_BYTES) { "SVG source exceeds the 16 MB safety limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Cannot read SVG file.")
    val svg = com.caverock.androidsvg.SVG.getFromString(bytes.toString(Charsets.UTF_8))
    val viewBox = svg.documentViewBox
    val width = svg.documentWidth.takeIf { it.isFinite() && it > 0f } ?: viewBox?.width()?.takeIf { it.isFinite() && it > 0f } ?: 1024f
    val height = svg.documentHeight.takeIf { it.isFinite() && it > 0f } ?: viewBox?.height()?.takeIf { it.isFinite() && it > 0f } ?: 1024f
    val maxPixels = imageDecodePixelBudget(context)
    val scale = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / (width.toDouble() * height.toDouble())))
    val outWidth = (width * scale).roundToInt().coerceIn(1, MAX_SVG_DIMENSION)
    val outHeight = (height * scale).roundToInt().coerceIn(1, MAX_SVG_DIMENSION)
    val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    return try {
        val canvas = android.graphics.Canvas(bitmap)
        svg.renderToCanvas(canvas, android.graphics.RectF(0f, 0f, outWidth.toFloat(), outHeight.toFloat()))
        bitmap
    } catch (error: Exception) {
        bitmap.recycle()
        throw error
    }
}

private fun decodeAnimatedImage(context: android.content.Context, uri: Uri): Drawable? {
    if (android.os.Build.VERSION.SDK_INT < 28) return null
    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
    return android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0) { "Invalid animated image dimensions." }
        val maxPixels = imageDecodePixelBudget(context, animated = true)
        val scale = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / (width.toLong() * height.toLong())))
        if (scale < 1.0) decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

@Composable
private fun AnimatedImageViewer(file: DocumentFile, drawable: Drawable, onBack: () -> Unit) {
    val context = LocalContext.current
    var menuExpanded by remember(file.uri) { mutableStateOf(false) }
    var metadataVisible by remember(file.uri) { mutableStateOf(false) }
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(drawable) } },
            update = { if (it.drawable !== drawable) it.setImageDrawable(drawable) },
            modifier = Modifier.fillMaxSize()
        )
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xCC101116), shape = MaterialTheme.shapes.extraLarge) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            }
            Text(file.name ?: "Animation", Modifier.weight(1f).padding(horizontal = 12.dp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(color = Color(0xCC101116), shape = MaterialTheme.shapes.extraLarge) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Image options", tint = Color.White) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("View metadata") }, onClick = { menuExpanded = false; metadataVisible = true })
                        DropdownMenuItem(text = { Text("Share image") }, onClick = {
                            menuExpanded = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = file.type ?: "image/gif"
                                putExtra(Intent.EXTRA_STREAM, file.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, "Share animation")) }
                        })
                    }
                }
            }
        }
        if (metadataVisible) AlertDialog(
            onDismissRequest = { metadataVisible = false },
            title = { Text("Image metadata") },
            text = { Column { Text("File: ${file.name}"); Text("Dimensions: ${drawable.intrinsicWidth} × ${drawable.intrinsicHeight} px"); Text("Size: ${android.text.format.Formatter.formatFileSize(context, file.length())}"); Text("Animation: ${if (drawable is AnimatedImageDrawable) "Animated" else "Static"}") } },
            confirmButton = { TextButton(onClick = { metadataVisible = false }) { Text("Close") } }
        )
    }
}

private fun decodePreviewBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        try {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0) { "Invalid image dimensions." }
                // Bound a single preview bitmap to at most 4 MP; full-resolution
                // tiled viewing will be handled separately from this safe preview.
                val maxPixels = imageDecodePixelBudget(context)
                val scale = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / (width.toLong() * height.toLong())))
                if (scale < 1.0) decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("AtomosImage", "ImageDecoder failed for $uri; trying BitmapFactory", error)
        }
    }
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: error("Cannot read image.")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val maxPixels = imageDecodePixelBudget(context)
    val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    val scale = kotlin.math.sqrt(maxPixels.toDouble() / sourcePixels).coerceAtMost(1.0)
    val desiredSample = kotlin.math.ceil(1.0 / scale).toInt().coerceAtLeast(1)
    val sampleSize = Integer.highestOneBit(desiredSample).let { if (it < desiredSample) it shl 1 else it }.coerceAtLeast(1)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
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
    val cosine = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
    val sine = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
    val rotatedWidth = kotlin.math.abs(width * cosine) + kotlin.math.abs(height * sine)
    val rotatedHeight = kotlin.math.abs(width * sine) + kotlin.math.abs(height * cosine)
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
