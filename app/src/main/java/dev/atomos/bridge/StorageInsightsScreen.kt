package dev.atomos.bridge

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class StorageReport(
    val total: Long, val free: Long, val files: Int, val folders: Int,
    val images: Long, val videos: Long, val audio: Long, val archives: Long,
    val packages: Long, val largest: List<Pair<String, Long>>, val truncated: Boolean
)

@Composable
fun StorageInsightsScreen() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            report = withContext(Dispatchers.IO) {
                val root = Environment.getExternalStorageDirectory()
                val stat = StatFs(root.absolutePath)
                var files = 0
                var folders = 0
                var images = 0L; var videos = 0L; var audio = 0L; var archives = 0L; var packages = 0L
                var scanned = 0
                val largest = mutableListOf<Pair<String, Long>>()
                val stack = ArrayDeque<Pair<File, Int>>()
                stack.add(root to 0)
                while (stack.isNotEmpty() && scanned < 5000) {
                    val (dir, depth) = stack.removeLast()
                    val children = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
                    for (child in children) {
                        if (scanned++ >= 5000) break
                        if (child.isDirectory) {
                            folders++
                            if (depth < 3 && child.canRead()) stack.add(child to depth + 1)
                        } else if (child.isFile) {
                            files++
                            val size = child.length().coerceAtLeast(0L)
                            when (child.extension.lowercase()) {
                                "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> images += size
                                "mp4", "mkv", "mov", "webm", "3gp" -> videos += size
                                "mp3", "m4a", "aac", "ogg", "wav", "flac", "opus" -> audio += size
                                "zip", "7z", "rar", "tar", "gz" -> archives += size
                                "apk", "apkm", "apks", "xapk", "apkx" -> packages += size
                            }
                            if (size > 0) {
                                largest.add(child.name to size)
                                if (largest.size > 30) {
                                    largest.sortByDescending { it.second }
                                    largest.removeAt(largest.lastIndex)
                                }
                            }
                        }
                    }
                }
                StorageReport(stat.totalBytes, stat.availableBytes, files, folders, images, videos, audio, archives, packages, largest.sortedByDescending { it.second }.take(8), scanned >= 5000)
            }
        } catch (e: Exception) { error = e.message ?: "Storage scan failed." }
        finally { loading = false }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
        item { Text("Storage", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("INTERNAL STORAGE", color = Violet, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    report?.let { r ->
                        Text(android.text.format.Formatter.formatFileSize(context, r.total - r.free) + " used", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { if (r.total > 0) ((r.total - r.free).toFloat() / r.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                        Text(android.text.format.Formatter.formatFileSize(context, r.free) + " available of " + android.text.format.Formatter.formatFileSize(context, r.total), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${r.files} files · ${r.folders} folders scanned", style = MaterialTheme.typography.labelMedium)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { refresh++ }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Refresh analysis") }
                }
            }
        }
        report?.let { r ->
            item { Text("Category footprint", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Images" to r.images, "Videos" to r.videos, "Audio" to r.audio, "Archives" to r.archives, "Packages" to r.packages).forEach { (label, bytes) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label); Text(android.text.format.Formatter.formatFileSize(context, bytes), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text("Category totals cover the scanned folders only.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("Largest files found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            items(r.largest.size) { index ->
                val (name, size) = r.largest[index]
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(android.text.format.Formatter.formatFileSize(context, size), color = Violet)
                    }
                }
            }
            if (r.truncated) item { Text("Scan limit reached. Refresh later or use a narrower folder for a deeper inspection.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        item { Text("Analysis is read-only. Atomos never removes files automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}
