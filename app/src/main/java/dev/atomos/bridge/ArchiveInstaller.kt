package dev.atomos.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.util.zip.ZipFile

object ArchiveInstaller {
    data class Prepared(val files: List<File>, val label: String)

    fun prepare(context: Context, source: File): Prepared {
        val ext = source.extension.lowercase()
        if (ext == "apk") return Prepared(listOf(source), source.name)
        require(ext in setOf("apkm", "apks", "apkx", "xapk", "zip")) {
            "Choose an APK, APKM, APKS, APKX or XAPK file."
        }
        val out = File(context.cacheDir, "install-${System.currentTimeMillis()}").apply { mkdirs() }
        val apks = mutableListOf<Pair<String, File>>()
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".apk") && !entry.name.contains("../")) {
                    val target = File(out, "split-${apks.size}.apk")
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    if (target.length() > 0) apks += Pair(entry.name, target) else target.delete()
                }
            }
        }
        require(apks.isNotEmpty()) { "No APK files were found in this archive." }
        val ordered = apks.sortedBy { pair -> if (pair.first.substringAfterLast("/").lowercase() == "base.apk") 0 else 1 }.map { it.second }
        require(ordered.size <= 32) { "This archive contains too many APK splits." }
        require(ordered.sumOf { it.length() } <= 1_000_000_000L) { "This archive is larger than the supported install limit." }
        return Prepared(ordered, source.name)
    }

    fun install(context: Context, files: List<File>) {
        require(files.isNotEmpty()) { "No APK files selected." }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            error("Allow Atomos to install unknown apps, then try again.")
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                files.forEachIndexed { index, file ->
                    require(file.isFile && file.length() > 0) { "An APK in the selected package is empty or missing." }
                    val name = if (index == 0) "base.apk" else "split-${index}.apk"
                    file.inputStream().use { input ->
                        session.openWrite(name, 0, file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                val pending = android.app.PendingIntent.getBroadcast(
                    context, id, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}
