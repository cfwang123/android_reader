package com.whj.reader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.whj.reader.BuildConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从 GitHub Releases 检查最新 APK 并下载安装。
 * API: https://api.github.com/repos/cfwang123/android_reader/releases/latest
 */
object AppUpdate {

    private const val API_LATEST =
        "https://api.github.com/repos/cfwang123/android_reader/releases/latest"
    private const val USER_AGENT = "WhjReader-Update/${BuildConfig.VERSION_NAME}"
    private const val CONNECT_MS = 20_000
    private const val READ_MS = 60_000

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long,
        val body: String,
        val htmlUrl: String,
    )

    sealed class CheckResult {
        data class UpdateAvailable(val info: ReleaseInfo) : CheckResult()
        data class UpToDate(val current: String, val latest: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    /**
     * 网络 IO，请在后台线程调用。
     */
    fun checkLatest(): CheckResult {
        return try {
            val json = httpGetJson(API_LATEST)
                ?: return CheckResult.Error("empty response")
            val tag = json.optString("tag_name", "").trim()
            if (tag.isBlank()) {
                return CheckResult.Error("no tag_name")
            }
            val version = normalizeVersion(tag)
            val assets = json.optJSONArray("assets")
                ?: return CheckResult.Error("no assets")
            var apkUrl: String? = null
            var apkName: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url", "")
                if (url.isBlank()) continue
                // 优先 reader*.apk
                if (apkUrl == null || name.startsWith("reader", ignoreCase = true)) {
                    apkUrl = url
                    apkName = name
                    size = a.optLong("size", 0L)
                    if (name.startsWith("reader", ignoreCase = true)) break
                }
            }
            if (apkUrl.isNullOrBlank() || apkName.isNullOrBlank()) {
                return CheckResult.Error("no apk asset")
            }
            val info = ReleaseInfo(
                tagName = tag,
                versionName = version,
                apkUrl = apkUrl,
                apkName = apkName,
                sizeBytes = size,
                body = json.optString("body", ""),
                htmlUrl = json.optString("html_url", ""),
            )
            val cmp = compareVersions(version, normalizeVersion(BuildConfig.VERSION_NAME))
            if (cmp > 0) {
                CheckResult.UpdateAvailable(info)
            } else {
                CheckResult.UpToDate(BuildConfig.VERSION_NAME, version)
            }
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 下载到 cacheDir/updates/；[cancel] 为 true 时中止。
     * [onProgress] 0..100，未知总长时可能长期为 -1 再跳到 100。
     */
    fun downloadApk(
        info: ReleaseInfo,
        destDir: File,
        cancel: AtomicBoolean,
        onProgress: (percent: Int) -> Unit,
    ): Result<File> {
        return try {
            destDir.mkdirs()
            val dest = File(destDir, sanitizeFileName(info.apkName))
            if (dest.exists()) dest.delete()
            val partial = File(destDir, dest.name + ".part")
            if (partial.exists()) partial.delete()

            val conn = openGet(info.apkUrl, followRedirects = true)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return Result.failure(IllegalStateException("HTTP $code"))
                }
                val total = conn.contentLengthLong.let { if (it > 0) it else info.sizeBytes }
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(partial).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var lastPct = -1
                        while (true) {
                            if (cancel.get()) {
                                return Result.failure(InterruptedException("cancelled"))
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) {
                                val pct = ((readTotal * 100) / total).toInt().coerceIn(0, 99)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                if (cancel.get()) {
                    partial.delete()
                    return Result.failure(InterruptedException("cancelled"))
                }
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
                onProgress(100)
                if (!dest.isFile || dest.length() <= 0L) {
                    dest.delete()
                    return Result.failure(IllegalStateException("empty file"))
                }
                Result.success(dest)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun updateCacheDir(context: Context): File =
        File(context.cacheDir, "updates")

    /** 去掉 v 前缀：v1.0.2 → 1.0.2 */
    fun normalizeVersion(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("v", ignoreCase = true) && s.length > 1 && s[1].isDigit()) {
            s = s.substring(1)
        }
        // 若 tag 含其它后缀，只取类似 x.y.z 段
        val m = Regex("""\d+(?:\.\d+)*""").find(s)
        return m?.value ?: s
    }

    /**
     * @return >0 a 更新；0 相同；<0 a 更旧
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = normalizeVersion(a).split('.').map { it.toIntOrNull() ?: 0 }
        val pb = normalizeVersion(b).split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun httpGetJson(url: String): JSONObject? {
        val conn = openGet(url, followRedirects = true)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.isBlank()) return null
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun openGet(url: String, followRedirects: Boolean): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            // 避免压缩后 content-length 不准
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun sanitizeFileName(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (n.isBlank()) "update.apk" else n
    }
}
