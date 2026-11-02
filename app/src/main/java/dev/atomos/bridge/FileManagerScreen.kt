package dev.atomos.bridge

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FileViolet = Color(0xFF9B8CFF)
private val FileSurface = Color(0xFF151A27)
private val FileMuted = Color(0xFF9AA3B5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    onNavigate: (String) -> Unit,
    onInstallPackage: (DocumentFile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SecureStore(context) }
    var rootUri by remember { mutableStateOf<Uri?>( (store.get("file_root_uri") ?: store.get("folder_uri"))?.let(Uri::parse) ) }
    val path = remember { mutableStateListOf<DocumentFile>() }
    var entries by remember { mutableStateOf(emptyList<DocumentFile>()) }
    var loading by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("Name") }
    var category by remember { mutableStateOf("All files") }
    var menuOpen by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<DocumentFile?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<DocumentFile?>(null) }
    var renameName by remember { mutableStateOf("") }
    var detailsTarget by remember { mutableStateOf<DocumentFile?>(null) }
    var transferSource by remember { mutableStateOf<DocumentFile?>(null) }
    var transferMove by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }
    var previewFile by remember { mutableStateOf<DocumentFile?>(null) }
    var busyFiles by remember { mutableStateOf(false) }

    val current = path.lastOrNull() ?: rootUri?.let { DocumentFile.fromTreeUri(context, it) }
    fun reload() {
        val dir = current
        scope.launch {
            loading = true
            entries = withContext(Dispatchers.IO) {
                dir?.listFiles()?.filter { showHidden || !(it.name ?: "").startsWith(".") }?.toList().orEmpty()
            }
            loading = false
        }
    }
    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            rootUri = uri
            store.put("file_root_uri", uri.toString()); store.put("folder_uri", uri.toString())
            path.clear()
            category = "All files"
        }
    }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val source = transferSource
        if (uri != null && source != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val destination = DocumentFile.fromTreeUri(context, uri) ?: error("Cannot open destination folder.")
                        val original = source.name ?: "file"
                        var targetName = original
                        var suffix = 1
                        while (destination.findFile(targetName) != null) {
                            val dot = original.lastIndexOf('.')
                            targetName = if (dot > 0) "${original.substring(0, dot)} ($suffix)${original.substring(dot)}" else "$original ($suffix)"
                            suffix++
                        }
                        val target = destination.createFile(source.type ?: "application/octet-stream", targetName)
                            ?: error("Could not create the destination file.")
                        try {
                            context.contentResolver.openInputStream(source.uri)?.use { input ->
                                context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) }
                                    ?: error("Could not write the destination file.")
                            } ?: error("Could not read the source file.")
                            if (transferMove && !source.delete()) error("Copied, but Android could not delete the original.")
                        } catch (e: Exception) {
                            target.delete()
                            throw e
                        }
                    }
                    toast = if (transferMove) "File moved" else "File copied"
                    reload()
                } catch (e: Exception) {
                    toast = e.message ?: "Transfer failed"
                } finally {
                    transferSource = null
                    transferMove = false
                }
            }
        } else {
            transferSource = null
            transferMove = false
        }
    }

    LaunchedEffect(rootUri, path.size, path.lastOrNull()?.uri, showHidden) { reload() }

    val filtered = remember(entries, search, category, sortBy) {
        entries.filter { file ->
            val name = file.name.orEmpty()
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = file.type.orEmpty()
            val categoryMatch = file.isDirectory || when (category) {
                "Images" -> mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "svg")
                "Videos" -> mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "3gp")
                "Audio" -> mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac")
                "Documents" -> ext in setOf("pdf", "doc", "docx", "txt", "rtf", "xls", "xlsx", "ppt", "pptx", "csv")
                "APKs" -> ext in setOf("apk", "apkm", "apks", "apkx", "xapk")
                else -> true
            }
            categoryMatch && name.contains(search, ignoreCase = true)
        }.sortedWith(when (sortBy) {
            "Size" -> compareByDescending<DocumentFile> { it.isDirectory }.thenByDescending { it.length() }
            "Modified" -> compareByDescending<DocumentFile> { it.isDirectory }.thenByDescending { it.lastModified() }
            else -> compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase().orEmpty() }
        })
    }

    fun openFile(file: DocumentFile) {
        val ext = file.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in setOf("apk", "apkm", "apks", "apkx", "xapk")) {
            onInstallPackage(file)
        } else if (ext == "zip" || ext in setOf("txt","md","log","json","xml","csv","kt","java","js","ts","css","html","htm","yaml","yml","properties","gradle","sh","py","jpg","jpeg","png","webp","gif","bmp","heic","avif","mp3","m4a","aac","ogg","wav","flac","opus","mp4","mkv","mov","webm","3gp","m4v")) {
            previewFile = file
        } else {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(file.uri, file.type ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Open with"))
            }.onFailure { toast = "No compatible viewer found" }
        }
    }
    fun createFolder() {
        val name = createName.trim()
        if (name.isEmpty()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { current?.createDirectory(name) }
            toast = if (result != null) "Folder created" else "Could not create folder"
            createName = ""
            createDialog = false
            reload()
        }
    }
    fun deleteFile(file: DocumentFile) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { file.delete() }
            toast = if (ok) "Deleted" else "Android could not delete this item"
            reload()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = FileSurface, drawerContentColor = MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("ATOMOS", fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = FileViolet, fontSize = 20.sp)
                    Text("FILE EXPLORER", style = MaterialTheme.typography.labelSmall, color = FileMuted)
                }
                HorizontalDivider(color = Color.White.copy(alpha = .08f))
                NavigationDrawerItem(label = { Text("All files") }, selected = category == "All files", onClick = { category = "All files"; scope.launch { drawerState.close() } }, icon = { Text("▤") }, modifier = Modifier.padding(horizontal = 12.dp))
                listOf("Images" to "▧", "Videos" to "▷", "Audio" to "♫", "Documents" to "▧", "APKs" to "⬇").forEach { (name, glyph) ->
                    NavigationDrawerItem(label = { Text(name) }, selected = category == name, onClick = { category = name; scope.launch { drawerState.close() } }, icon = { Text(glyph) }, modifier = Modifier.padding(horizontal = 12.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp, horizontal = 16.dp), color = Color.White.copy(alpha = .08f))
                NavigationDrawerItem(label = { Text("Network") }, selected = false, onClick = { scope.launch { drawerState.close() }; onNavigate("Network") }, icon = { Text("⌁") }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Package installer") }, selected = false, onClick = { scope.launch { drawerState.close() }; onNavigate("Installer") }, icon = { Text("⬇") }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = { scope.launch { drawerState.close() }; onNavigate("Settings") }, icon = { Text("⚙") }, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(16.dp))
                Text("Storage access is limited to folders you grant.", style = MaterialTheme.typography.labelSmall, color = FileMuted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }
    ) {
        Scaffold(
            containerColor = Color(0xFF090B12),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (searchOpen) "Search files" else "Files", fontWeight = FontWeight.Bold)
                            Text(current?.name ?: "Choose a storage location", style = MaterialTheme.typography.labelSmall, color = FileMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open navigation drawer")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) search = "" }) { Icon(Icons.Default.Search, contentDescription = "Search") }
                        IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView, contentDescription = "Change layout") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("New folder") }, onClick = { menuOpen = false; createDialog = true })
                                DropdownMenuItem(text = { Text("Sort: $sortBy") }, onClick = { sortBy = when (sortBy) { "Name" -> "Modified"; "Modified" -> "Size"; else -> "Name" }; menuOpen = false })
                                DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") }, onClick = { showHidden = !showHidden; menuOpen = false })
                                DropdownMenuItem(text = { Text("Refresh") }, onClick = { menuOpen = false; reload() })
                                DropdownMenuItem(text = { Text("Choose storage") }, onClick = { menuOpen = false; rootPicker.launch(null) })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF090B12))
                )
            },
            floatingActionButton = {
                if (rootUri != null) {
                    FloatingActionButton(onClick = { createDialog = true }, containerColor = FileViolet, contentColor = Color(0xFF10111A)) {
                        Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (searchOpen) {
                    OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Search in this folder") }, singleLine = true)
                }
                if (rootUri != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (path.isNotEmpty()) IconButton(onClick = { path.removeAt(path.lastIndex) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ArrowBack, contentDescription = "Go up one folder", tint = FileViolet) }
                        Text("Storage", color = FileMuted, style = MaterialTheme.typography.labelMedium)
                        path.forEachIndexed { index, folder ->
                            Text("  /  ", color = FileMuted)
                            Text(folder.name ?: "Folder", color = if (index == path.lastIndex) FileViolet else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { while (path.size > index + 1) path.removeAt(path.lastIndex) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All files", "Images", "Videos", "Audio", "Documents", "APKs").forEach { label ->
                            FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label, maxLines = 1) })
                        }
                    }
                }
                when {
                    rootUri == null -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▤", fontSize = 58.sp, color = FileViolet)
                        Spacer(Modifier.height(16.dp))
                        Text("Your files, in one place.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Choose a folder or storage location to start browsing. Atomos only accesses the location you select.", color = FileMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                        Button(onClick = { rootPicker.launch(null) }) { Text("Choose storage") }
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FileViolet) }
                    filtered.isEmpty() -> Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (search.isNotBlank()) "No matching files" else "Nothing here yet", style = MaterialTheme.typography.titleLarge)
                            Text(if (search.isNotBlank()) "Try another search." else "Create a folder or choose another location.", color = FileMuted)
                        }
                    }
                    grid -> LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        gridItems(filtered, key = { it.uri.toString() }) { file ->
                            FileGridTile(file, onClick = { if (file.isDirectory) path.add(file) else openFile(file) }, onMenu = { itemMenu = file })
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { it.uri.toString() }) { file ->
                            FileListRow(file, onClick = { if (file.isDirectory) path.add(file) else openFile(file) }, onMenu = { itemMenu = file })
                        }
                    }
                }
                if (toast.isNotBlank()) Text(toast, color = FileViolet, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            }
        }
    }

    if (busyFiles) AlertDialog(onDismissRequest = {}, title = { Text("Processing archive") }, text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(12.dp)); Text("Working locally…") } }, confirmButton = {})
    previewFile?.let { file -> FilePreviewScreen(file, onBack = { previewFile = null }) }
    itemMenu?.let { file ->
        AlertDialog(
            onDismissRequest = { itemMenu = null },
            title = { Text(file.name ?: "File options", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { itemMenu = null; if (file.isDirectory) path.add(file) else openFile(file) }, modifier = Modifier.fillMaxWidth()) { Text("Open", modifier = Modifier.fillMaxWidth()) }
                    if (!file.isDirectory && file.name?.substringAfterLast('.', "")?.lowercase() == "zip") {
                        TextButton(onClick = { itemMenu = null; busyFiles = true; scope.launch { try { val n = withContext(Dispatchers.IO) { extractZip(context, file, current) }; toast = "Extracted $n entries"; reload() } catch (e: Exception) { toast = e.message ?: "Extraction failed" } finally { busyFiles = false } } }, modifier = Modifier.fillMaxWidth()) { Text("Extract here", modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(onClick = { itemMenu = null; busyFiles = true; scope.launch { try { val result = withContext(Dispatchers.IO) { compressToZip(context, file, current) }; toast = "Created ${result.name}"; reload() } catch (e: Exception) { toast = e.message ?: "ZIP creation failed" } finally { busyFiles = false } } }, modifier = Modifier.fillMaxWidth()) { Text("Compress to ZIP", modifier = Modifier.fillMaxWidth()) }
                    if (!file.isDirectory) {
                        TextButton(onClick = { itemMenu = null; transferSource = file; transferMove = false; destinationPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Copy to…", modifier = Modifier.fillMaxWidth()) }
                        TextButton(onClick = { itemMenu = null; transferSource = file; transferMove = true; destinationPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Move to…", modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(onClick = { itemMenu = null; renameTarget = file; renameName = file.name.orEmpty() }, modifier = Modifier.fillMaxWidth()) { Text("Rename", modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = { itemMenu = null; detailsTarget = file }, modifier = Modifier.fillMaxWidth()) { Text("Details", modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = { itemMenu = null; deleteFile(file) }, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = { TextButton(onClick = { itemMenu = null }) { Text("Close") } }
        )
    }

    if (createDialog) AlertDialog(
        onDismissRequest = { createDialog = false },
        title = { Text("Create folder") },
        text = { OutlinedTextField(createName, { createName = it }, label = { Text("Folder name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { createFolder() }, enabled = createName.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } }
    )
    if (renameTarget != null) AlertDialog(
        onDismissRequest = { renameTarget = null },
        title = { Text("Rename") },
        text = { OutlinedTextField(renameName, { renameName = it }, label = { Text("New name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val target = renameTarget
            scope.launch {
                val ok = withContext(Dispatchers.IO) { target?.renameTo(renameName.trim()) == true }
                toast = if (ok) "Renamed" else "Could not rename"
                renameTarget = null
                reload()
            }
        }, enabled = renameName.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
    )
    detailsTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { detailsTarget = null },
            title = { Text(file.name ?: "File details") },
            text = { Text(if (file.isDirectory) "Folder\nLast modified: ${file.lastModified()}" else "Type: ${file.type ?: "Unknown"}\nSize: ${android.text.format.Formatter.formatShortFileSize(context, file.length())}\nModified: ${file.lastModified()}") },
            confirmButton = { TextButton(onClick = { detailsTarget = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun FileListRow(file: DocumentFile, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(fileGlyph(file), fontSize = 24.sp, color = FileViolet, modifier = Modifier.width(42.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name ?: "Unnamed", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (file.isDirectory) "Folder" else "${android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, file.length())}  ·  ${file.type ?: "File"}", color = FileMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = "File options", tint = FileMuted) }
    }
    HorizontalDivider(color = Color.White.copy(alpha = .045f), modifier = Modifier.padding(start = 54.dp))
}

@Composable
private fun FileGridTile(file: DocumentFile, onClick: () -> Unit, onMenu: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = FileSurface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().height(132.dp).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fileGlyph(file), fontSize = 28.sp, color = FileViolet)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onMenu, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "File options", tint = FileMuted) }
            }
            Column {
                Text(file.name ?: "Unnamed", fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (file.isDirectory) "Folder" else android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, file.length()), style = MaterialTheme.typography.labelSmall, color = FileMuted)
            }
        }
    }
}

private fun fileGlyph(file: DocumentFile): String {
    if (file.isDirectory) return "📁"
    return when (file.name?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic" -> "▧"
        "mp4", "mkv", "mov", "webm", "3gp" -> "▶"
        "mp3", "m4a", "aac", "ogg", "wav", "flac" -> "♫"
        "apk", "apkm", "apks", "apkx", "xapk" -> "⬇"
        "pdf" -> "PDF"
        "zip", "rar", "7z", "tar", "gz" -> "▣"
        "doc", "docx", "txt", "rtf" -> "▤"
        else -> "•"
    }
}

private fun compressToZip(context: android.content.Context, source: DocumentFile, destination: DocumentFile?): DocumentFile {
    val parent = destination ?: error("Choose a storage folder first.")
    val stem = (source.name ?: "archive").substringBeforeLast('.', source.name ?: "archive")
    var name = "$stem.zip"; var i = 1
    while (parent.findFile(name) != null) { name = "$stem ($i).zip"; i++ }
    val target = parent.createFile("application/zip", name) ?: error("Cannot create ZIP.")
    try {
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(context.contentResolver.openOutputStream(target.uri, "w") ?: error("Cannot write ZIP."))).use { zip ->
            val buffer = ByteArray(65536)
            fun add(item: DocumentFile, entryPath: String) {
                if (item.isDirectory) {
                    val dirPath = if (entryPath.endsWith("/")) entryPath else "$entryPath/"
                    zip.putNextEntry(java.util.zip.ZipEntry(dirPath)); zip.closeEntry()
                    item.listFiles().forEach { add(it, "$dirPath${it.name ?: "unnamed"}") }
                } else {
                    zip.putNextEntry(java.util.zip.ZipEntry(entryPath))
                    context.contentResolver.openInputStream(item.uri)?.use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) } } ?: error("Cannot read ${item.name}.")
                    zip.closeEntry()
                }
            }
            add(source, source.name ?: "item")
        }
        return target
    } catch (e: Exception) { target.delete(); throw e }
}
private fun extractZip(context: android.content.Context, archive: DocumentFile, destination: DocumentFile?): Int {
    val root = destination ?: error("Choose a storage folder first.")
    var count = 0; val buffer = ByteArray(65536)
    java.util.zip.ZipInputStream(java.io.BufferedInputStream(context.contentResolver.openInputStream(archive.uri) ?: error("Cannot read ZIP."))).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val normalized = entry.name.replace('\\', '/')
            val parts = normalized.split('/').filter { it.isNotBlank() }
            require(parts.none { it == "." || it == ".." || it.contains(':') }) { "Unsafe ZIP entry path rejected." }
            var dir = root
            parts.dropLast(if (entry.isDirectory) 0 else 1).forEach { part -> dir = dir.findFile(part) ?: dir.createDirectory(part) ?: error("Cannot create folder.") }
            if (!entry.isDirectory && parts.isNotEmpty()) {
                val output = dir.createFile("application/octet-stream", parts.last()) ?: error("Cannot create ${parts.last()}.")
                try { context.contentResolver.openOutputStream(output.uri, "w")?.use { out -> while (true) { val n = zip.read(buffer); if (n < 0) break; out.write(buffer, 0, n) } } ?: error("Cannot write extracted file.") }
                catch (e: Exception) { output.delete(); throw e }
                count++
            }
            zip.closeEntry()
        }
    }
    return count
}
