package com.whj.reader.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.whj.reader.R
import com.whj.reader.data.BookChineseModeStore
import com.whj.reader.data.BookEncodingStore
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.ChineseConvert
import com.whj.reader.data.ReadingProgressStore
import java.io.FileNotFoundException

/**
 * 打开失败引导：区分权限 / 文件失效，提供「授予权限」「重新选文件」。
 */
object OpenFailGuide {

    enum class Reason {
        /** 读权限不足 */
        PERMISSION,
        /** 文件移动、删除或授权失效 */
        UNAVAILABLE,
        /** 其它错误 */
        GENERIC,
    }

    fun reasonFrom(error: Throwable?): Reason {
        if (error == null) return Reason.UNAVAILABLE
        if (error is SecurityException) return Reason.PERMISSION
        if (error is FileNotFoundException) return Reason.UNAVAILABLE
        val msg = (error.message ?: error.javaClass.simpleName).lowercase()
        if (msg.contains("permission") ||
            msg.contains("权限") ||
            msg.contains("eacces") ||
            msg.contains("eperm") ||
            msg.contains("security")
        ) {
            return Reason.PERMISSION
        }
        if (msg.contains("enoent") ||
            msg.contains("no such file") ||
            msg.contains("not found") ||
            msg.contains("找不到") ||
            msg.contains("无法打开") ||
            msg.contains("cannot open") ||
            msg.contains("file not found")
        ) {
            return Reason.UNAVAILABLE
        }
        return Reason.GENERIC
    }

    /** 当前是否还能引导用户去授权（全盘或旧版存储读权限） */
    fun canOfferGrantPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return !StorageAccess.hasAllFilesAccess()
        }
        if (Build.VERSION.SDK_INT >= 33) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    }

    fun message(context: Context, reason: Reason, detail: String?): String {
        return when (reason) {
            Reason.PERMISSION -> context.getString(R.string.open_failed_permission)
            Reason.UNAVAILABLE -> context.getString(R.string.open_failed_unavailable)
            Reason.GENERIC -> {
                val d = detail?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.load_failed, "")
                context.getString(R.string.open_failed_generic, d.trim())
            }
        }
    }

    /**
     * @param onGrantPermission 用户点「授予权限」
     * @param onReselect 用户点「重新选文件」
     * @param onClose 关闭/取消（阅读页通常 finish）
     */
    fun show(
        activity: AppCompatActivity,
        reason: Reason,
        detail: String? = null,
        bookTitle: String? = null,
        onGrantPermission: (() -> Unit)? = null,
        onReselect: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    ) {
        val title = bookTitle?.takeIf { it.isNotBlank() }?.let {
            activity.getString(R.string.open_failed_title_named, it)
        } ?: activity.getString(R.string.open_failed_title)

        val offerGrant = onGrantPermission != null && canOfferGrantPermission(activity)
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message(activity, reason, detail))
            .setCancelable(true)
            .setOnCancelListener { onClose?.invoke() }
            .setNegativeButton(R.string.open_failed_close) { _, _ -> onClose?.invoke() }

        when {
            onReselect != null && offerGrant -> {
                builder.setPositiveButton(R.string.open_failed_reselect) { _, _ -> onReselect() }
                builder.setNeutralButton(R.string.open_failed_grant_permission) { _, _ ->
                    onGrantPermission?.invoke()
                }
            }
            onReselect != null -> {
                builder.setPositiveButton(R.string.open_failed_reselect) { _, _ -> onReselect() }
            }
            offerGrant -> {
                builder.setPositiveButton(R.string.open_failed_grant_permission) { _, _ ->
                    onGrantPermission?.invoke()
                }
            }
        }
        builder.show()
    }

    /**
     * 重新选文件后：持久授权、尽量稳定 URI、迁移进度/编码/书架绑定。
     * @return 可打开的 uri 字符串
     */
    fun bindReselectedFile(
        context: Context,
        oldUri: String?,
        newUri: Uri,
        displayName: String?,
        bookId: String? = null,
    ): String {
        val stable = StorageAccess.ensurePersistentReadable(context, newUri, displayName)
        if (!oldUri.isNullOrBlank() && oldUri != stable) {
            migrateBindings(context, oldUri, stable, bookId)
        } else if (bookId != null &&
            !bookId.startsWith("hist_") &&
            !oldUri.isNullOrBlank() &&
            oldUri == stable
        ) {
            // 同路径也刷新 lastOpened
            BookshelfStore.updateBookUri(context, bookId, stable)
        } else if (bookId != null && !bookId.startsWith("hist_")) {
            BookshelfStore.updateBookUri(context, bookId, stable)
        }
        return stable
    }

    fun migrateBindings(
        context: Context,
        oldUri: String,
        newUri: String,
        bookId: String? = null,
    ) {
        if (oldUri.isBlank() || newUri.isBlank() || oldUri == newUri) return
        ReadingProgressStore.migrate(context, oldUri, newUri)
        BookEncodingStore.get(context, oldUri)?.let {
            BookEncodingStore.set(context, newUri, it)
        }
        val zh = BookChineseModeStore.get(context, oldUri)
        if (zh != ChineseConvert.Mode.OFF) {
            BookChineseModeStore.set(context, newUri, zh)
        }
        val id = bookId?.takeIf { !it.startsWith("hist_") }
            ?: BookshelfStore.findBookByUri(context, oldUri)?.id
        if (id != null) {
            BookshelfStore.updateBookUri(context, id, newUri)
        }
    }
}
