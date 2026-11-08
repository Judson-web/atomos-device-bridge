package dev.atomos.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

object ArchiveInstaller {
    data class Prepared(val files: List<File>, val label: String, val packageName: String)

    private const val MAX_SOURCE_BYTES = 1_000_000_000L
    private const val MAX_EXPANDED_APK_BYTES = 1_000_000_000L
    private const val MAX_ARCHIVE_ENTRIES = 20_000
    private const val MAX_APK_SPLITS = 32

    fun prepare(context: Context, source: File): Prepared {
        if (!source.isFile || source.length() <= 0L) {
            source.delete()
            error("The selected package is empty or unavailable.")
        }
        if (source.length() > MAX_SOURCE_BYTES) {
            source.delete()
            error("The selected package exceeds the 1 GB source limit.")
        }

        val ext = source.extension.lowercase()
        if (ext == "apk") return Prepared(listOf(source), source.name, packageNameFor(context, source))
        if (ext !in setOf("apkm", "apks", "apkx", "xapk", "zip")) {
            source.delete()
            error("Choose an APK, APKM, APKS, APKX or XAPK file.")
        }

        val out = File(context.cacheDir, "install-${UUID.randomUUID()}").apply {
            require(mkdirs()) { "Unable to prepare the package in app storage." }
        }
        val apks = mutableListOf<Pair<String, File>>()
        var totalBytes = 0L

        try {
            ZipFile(source).use { zip ->
                val entries = zip.entries()
                var scannedEntries = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    scannedEntries++
                    require(scannedEntries <= MAX_ARCHIVE_ENTRIES) { "Archive contains too many entries." }
                    if (entry.isDirectory || !entry.name.lowercase().endsWith(".apk")) continue

                    val normalizedName = entry.name.replace('\\', '/')
                    val parts = normalizedName.split('/').filter { it.isNotBlank() }
                    require(
                        !normalizedName.startsWith("/") &&
                            parts.isNotEmpty() &&
                            parts.none { it == "." || it == ".." || it.contains(':') }
                    ) { "Archive contains an unsafe APK path." }
                    require(apks.size < MAX_APK_SPLITS) { "This archive contains too many APK splits." }

                    val target = File(out, "split-${apks.size}.apk")
                    var entryBytes = 0L
                    try {
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    entryBytes += read
                                    totalBytes += read
                                    require(totalBytes <= MAX_EXPANDED_APK_BYTES) {
                                        "This archive expands beyond the supported 1 GB install limit."
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        require(entryBytes > 0L && target.length() == entryBytes) { "An APK in the archive is empty or incomplete." }
                        packageNameFor(context, target)
                        apks += Pair(normalizedName, target)
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                }
            }

            require(apks.isNotEmpty()) { "No APK files were found in this archive." }
            require(apks.count { it.first.substringAfterLast('/').equals("base.apk", ignoreCase = true) } == 1) {
                "This split package has no unique base.apk and cannot be installed safely."
            }
            val packageNames = apks.map { packageNameFor(context, it.second) }.toSet()
            require(packageNames.size == 1) { "The archive contains APKs from different Android packages." }
            val ordered = apks.sortedBy { (entryName, _) ->
                when (entryName.substringAfterLast('/').lowercase()) {
                    "base.apk" -> 0
                    else -> if (entryName.substringAfterLast('/').lowercase().startsWith("base-")) 1 else 2
                }
            }.map { it.second }
            source.delete()
            return Prepared(ordered, source.name, packageNames.single())
        } catch (e: Exception) {
            out.deleteRecursively()
            source.delete()
            throw e
        }
    }

    suspend fun install(context: Context, prepared: Prepared) {
        require(prepared.files.isNotEmpty()) { "No APK files selected." }
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                withContext(Dispatchers.Main.immediate) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                error("Allow Atomos to install unknown apps, then choose the package again.")
            }

            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val totalBytes = prepared.files.sumOf { it.length() }
                require(totalBytes > 0L) { "The selected package is empty." }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setSize(totalBytes)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAppPackageName(prepared.packageName)
                    }
                }
                val id = installer.createSession(params)
                try {
                    installer.openSession(id).use { session ->
                        prepared.files.forEachIndexed { index, file ->
                            require(file.isFile && file.length() > 0L) { "An APK in the selected package is empty or missing." }
                            val name = if (index == 0) "base.apk" else "split-${index}.apk"
                            file.inputStream().buffered().use { input ->
                                session.openWrite(name, 0, file.length()).use { output ->
                                    input.copyTo(output, 64 * 1024)
                                    session.fsync(output)
                                }
                            }
                        }
                        val intent = Intent(context, InstallResultReceiver::class.java)
                        val pending = android.app.PendingIntent.getBroadcast(
                            context,
                            id,
                            intent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                        )
                        session.commit(pending.intentSender)
                    }
                } catch (e: Exception) {
                    runCatching { installer.abandonSession(id) }
                    throw e
                }
            }
        } finally {
            cleanupTemporaryFiles(context, prepared.files)
        }
    }

    private fun packageNameFor(context: Context, file: File): String {
        require(file.isFile && file.length() > 0L) { "An APK in the selected package is empty." }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("An APK in the selected package is invalid or unreadable.")
        return info.packageName?.takeIf { it.isNotBlank() }
            ?: error("An APK in the selected package has no valid package identity.")
    }

    private fun cleanupTemporaryFiles(context: Context, files: List<File>) {
        val cacheRoot = context.cacheDir.canonicalFile
        val tempFiles = files.map { it.canonicalFile }.filter { file ->
            file.toPath().startsWith(cacheRoot.toPath())
        }
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.mapNotNull { it.parentFile }
            .filter { it.name.startsWith("install-") && it.toPath().startsWith(cacheRoot.toPath()) }
            .distinct()
            .forEach { runCatching { it.deleteRecursively() } }
    }
}
