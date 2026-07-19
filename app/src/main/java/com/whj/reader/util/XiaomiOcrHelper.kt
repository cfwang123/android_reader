package com.whj.reader.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File

/**
 * 探测 / 调用小米侧 OCR 能力（无公开 SDK，走扫一扫 Intent + 反射 OCREngine）。
 *
 * 设备上常见包：
 * - [PKG_SCANNER] 内嵌 com.xiaomi.ocr.sdk_ocr.OCREngine + libmiocr*.so
 * - [PKG_VISION]  AI 视觉翻译（权限多为 signature，第三方难直接用）
 */
object XiaomiOcrHelper {

    const val PKG_SCANNER = "com.xiaomi.scanner"
    const val PKG_VISION = "com.xiaomi.aiasst.vision"
    const val PKG_AICR = "com.xiaomi.aicr"

    private const val TAG = "XiaomiOcr"
    private const val ENGINE_CLASS = "com.xiaomi.ocr.sdk_ocr.OCREngine"

    data class PackageProbe(
        val packageName: String,
        val installed: Boolean,
        val label: String? = null,
        val versionName: String? = null,
        val sourceDir: String? = null,
        val nativeLibDir: String? = null,
        val note: String = "",
    )

    data class OcrAttempt(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val text: String? = null,
        val elapsedMs: Long = 0L,
    )

    fun probePackages(context: Context): List<PackageProbe> {
        val pm = context.packageManager
        return listOf(PKG_SCANNER, PKG_VISION, PKG_AICR).map { pkg ->
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val app = pm.getApplicationInfo(pkg, 0)
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()
                PackageProbe(
                    packageName = pkg,
                    installed = true,
                    label = label,
                    versionName = info.versionName,
                    sourceDir = app.sourceDir,
                    nativeLibDir = app.nativeLibraryDir,
                    note = when (pkg) {
                        PKG_SCANNER -> "含 OCREngine / libmiocr（可尝试反射 + SEND 图片）"
                        PKG_VISION -> "AI 视觉翻译，TranslateSdk 需系统签名权限"
                        PKG_AICR -> "小米 AI 核心，未见公开 OCR Intent"
                        else -> ""
                    },
                )
            } catch (_: PackageManager.NameNotFoundException) {
                PackageProbe(packageName = pkg, installed = false, note = "未安装")
            } catch (t: Throwable) {
                PackageProbe(packageName = pkg, installed = false, note = "探测失败: ${t.message}")
            }
        }
    }

    fun isScannerInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(PKG_SCANNER, 0)
            true
        }.getOrDefault(false)

    /**
     * 把图片交给「小米扫一扫」处理（打开对方 UI，用户可在其中识字）。
     * 不返回文本；适合验证系统能力是否可用。
     */
    fun buildSendToScannerIntent(imageUri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            setPackage(PKG_SCANNER)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun buildOpenScannerMainIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            setClassName(PKG_SCANNER, "com.xiaomi.scanner.app.ScanActivity")
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun canResolveSendToScanner(context: Context, imageUri: Uri): Boolean {
        val intent = buildSendToScannerIntent(imageUri)
        return intent.resolveActivity(context.packageManager) != null
    }

    /**
     * 进程内反射调用扫一扫 APK 里的 OCREngine。
     * 非公开 API，机型 / 版本差异大，失败时返回详细日志。
     */
    fun tryReflectOcr(context: Context, bitmap: Bitmap): OcrAttempt {
        val t0 = System.currentTimeMillis()
        val log = StringBuilder()
        fun line(s: String) {
            log.append(s).append('\n')
            Log.i(TAG, s)
        }

        return try {
            val pm = context.packageManager
            val app = try {
                pm.getApplicationInfo(PKG_SCANNER, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return OcrAttempt(
                    name = "反射 OCREngine",
                    ok = false,
                    detail = "未安装 $PKG_SCANNER",
                    elapsedMs = System.currentTimeMillis() - t0,
                )
            }

            val apkPath = app.sourceDir
            val nativeLibDir = app.nativeLibraryDir
            line("sourceDir=$apkPath")
            line("nativeLibDir=$nativeLibDir")

            val libDir = File(nativeLibDir)
            if (libDir.isDirectory) {
                val so = libDir.list()?.filter { it.contains("miocr", ignoreCase = true) }?.sorted().orEmpty()
                line("miocr so: ${so.joinToString().ifEmpty { "(无)" }}")
            } else {
                line("nativeLibDir 不可读或不存在")
            }

            // 预加载 native，减轻 static loadLibrary 失败概率
            preloadMiOcrLibs(nativeLibDir, ::line)

            val parent = context.classLoader
            val loader = PathClassLoader(apkPath, nativeLibDir, parent)
            line("PathClassLoader ok")

            val engineClass = Class.forName(ENGINE_CLASS, true, loader)
            line("loaded $ENGINE_CLASS")

            val engine = engineClass.getMethod("getInstance").invoke(null)
                ?: return OcrAttempt(
                    "反射 OCREngine",
                    false,
                    log.toString() + "getInstance()=null",
                    elapsedMs = System.currentTimeMillis() - t0,
                )
            line("getInstance ok: ${engine.javaClass.name}")

            val version = runCatching {
                engineClass.getMethod("version").invoke(engine) as? String
            }.getOrNull()
            if (version != null) line("version()=$version")

            val initMethod = engineClass.getMethod("init", String::class.java, String::class.java)
            val candidates = initPathCandidates(context, nativeLibDir, apkPath)
            var inited = false
            var usedPair: Pair<String, String>? = null
            for ((a, b) in candidates) {
                val ok = runCatching {
                    initMethod.invoke(engine, a, b) as Boolean
                }.getOrElse { e ->
                    line("init(\"$a\", \"$b\") 异常: ${e.message}")
                    false
                }
                line("init(\"$a\", \"$b\") => $ok")
                if (ok) {
                    inited = true
                    usedPair = a to b
                    break
                }
            }

            if (!inited) {
                return OcrAttempt(
                    name = "反射 OCREngine",
                    ok = false,
                    detail = log.toString() +
                        "init 全部失败。模型路径可能是私有下载目录，或需系统签名。\n" +
                        "可改用「发送到扫一扫」在系统 UI 里识字。",
                    elapsedMs = System.currentTimeMillis() - t0,
                )
            }
            line("init 成功 pair=$usedPair")

            val doOcr = engineClass.getMethod("doOCR", Bitmap::class.java)
            val result = doOcr.invoke(engine, bitmap)
            if (result == null) {
                runCatching { engineClass.getMethod("release").invoke(engine) }
                return OcrAttempt(
                    "反射 OCREngine",
                    false,
                    log.toString() + "doOCR 返回 null",
                    elapsedMs = System.currentTimeMillis() - t0,
                )
            }

            val text = extractTotalText(result)
            line("total_text length=${text?.length ?: 0}")

            runCatching { engineClass.getMethod("release").invoke(engine) }
            line("release ok")

            OcrAttempt(
                name = "反射 OCREngine",
                ok = !text.isNullOrBlank(),
                detail = log.toString() + if (text.isNullOrBlank()) "识别结果为空" else "识别成功",
                text = text,
                elapsedMs = System.currentTimeMillis() - t0,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "reflect ocr failed", t)
            OcrAttempt(
                name = "反射 OCREngine",
                ok = false,
                detail = log.toString() + "异常: ${t.javaClass.simpleName}: ${t.message}\n" +
                    (t.cause?.let { "cause: ${it.javaClass.simpleName}: ${it.message}\n" } ?: "") +
                    t.stackTrace.take(8).joinToString("\n") { "  at $it" },
                elapsedMs = System.currentTimeMillis() - t0,
            )
        }
    }

    private fun extractTotalText(result: Any): String? {
        // OCRData$OCRResult.total_text
        runCatching {
            val f = result.javaClass.getField("total_text")
            return f.get(result) as? String
        }
        runCatching {
            val m = result.javaClass.getMethod("getTotal_text")
            return m.invoke(result) as? String
        }
        // paragraphs[].paragraph_text / lines[].line_text
        runCatching {
            val paras = result.javaClass.getField("paragraphs").get(result) as? Array<*>
            if (paras != null) {
                val sb = StringBuilder()
                for (p in paras) {
                    if (p == null) continue
                    val t = runCatching {
                        p.javaClass.getField("paragraph_text").get(p) as? String
                    }.getOrNull()
                    if (!t.isNullOrBlank()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(t)
                    }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        }
        return result.toString().takeIf { it.isNotBlank() && !it.contains('@') }
    }

    private fun initPathCandidates(
        context: Context,
        nativeLibDir: String,
        apkPath: String,
    ): List<Pair<String, String>> {
        val ourFiles = context.filesDir.absolutePath
        val ourCache = context.cacheDir.absolutePath
        val apkDir = File(apkPath).parent ?: apkPath
        return listOf(
            nativeLibDir to nativeLibDir,
            nativeLibDir to ourFiles,
            nativeLibDir to ourCache,
            apkDir to nativeLibDir,
            apkPath to nativeLibDir,
            ourFiles to ourFiles,
            "" to "",
            nativeLibDir to "",
            "" to nativeLibDir,
        ).distinct()
    }

    private fun preloadMiOcrLibs(nativeLibDir: String, line: (String) -> Unit) {
        val names = listOf(
            "libc++_shared.so",
            "libmiocr.so",
            "libmiocr_tokenizer.so",
            "libmiocr_wrapper.so",
        )
        for (name in names) {
            val f = File(nativeLibDir, name)
            if (!f.isFile) {
                line("skip load (missing): ${f.absolutePath}")
                continue
            }
            try {
                System.load(f.absolutePath)
                line("System.load ok: $name")
            } catch (t: Throwable) {
                line("System.load fail $name: ${t.message}")
            }
        }
    }
}
