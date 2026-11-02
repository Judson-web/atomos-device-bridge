package dev.atomos.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

object ArchiveInstaller {
    data class Prepared(val files: List<File>, val label: String)
    fun prepare(context: Context, source: File): Prepared {
        val ext = source.extension.lowercase()
        if (ext == "apk") return Prepared(listOf(source), source.name)
        require(ext in setOf("apkm", "apks", "apkx", "xapk", "zip")) { "Choose an APK, APKM, APKS, APKX or XAPK file." }
        val out = File(context.cacheDir, "install-${System.currentTimeMillis()}").apply { mkdirs() }
        val apks = mutableListOf<File>()
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".apk") && !entry.name.contains("../")) {
                    val target = File(out, "split-${apks.size}.apk")
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    if (target.length() > 0) apks += target else target.delete()
                }
            }
        }
        require(apks.isNotEmpty()) { "No APK files were found in this archive." }
        return Prepared(apks, source.name)
    }
    fun install(context: Context, files: List<File>) {
        require(files.isNotEmpty()) { "No APK files selected." }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            error("Allow Atomos to install unknown apps, then try again.")
        }
        val installer = context.packageManager.packageInstaller
        if (files.size == 1) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                files.first().inputStream().use { input -> session.openWrite("base.apk", 0, files.first().length()).use { input.copyTo(it); session.fsync(it) } }
                val intent = Intent(context, InstallResultReceiver::class.java)
                val pending = android.app.PendingIntent.getBroadcast(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } else {
            val parentParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setMultiPackage() }
            val parentId = installer.createSession(parentParams)
            val childIds = mutableListOf<Int>()
            try {
                files.forEachIndexed { index, file ->
                    val childId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                    childIds += childId
                    installer.openSession(childId).use { session ->
                        file.inputStream().use { input -> session.openWrite(if (index == 0) "base.apk" else "split-${index}.apk", 0, file.length()).use { input.copyTo(it); session.fsync(it) } }
                    }
                }
                installer.openSession(parentId).use { parent ->
                    childIds.forEach { parent.addChildSessionId(it) }
                    val intent = Intent(context, InstallResultReceiver::class.java)
                    val pending = android.app.PendingIntent.getBroadcast(context, parentId, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
                    parent.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                childIds.forEach { runCatching { installer.abandonSession(it) } }
                runCatching { installer.abandonSession(parentId) }
                throw e
            }
        }
    }
}
