package com.whj.reader.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * 全局 Toast：新消息立即 cancel 旧的，避免排队堆叠。
 */
object Toasts {
    private var current: Toast? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val appCtx = context.applicationContext
        val run = Runnable {
            current?.cancel()
            current = Toast.makeText(appCtx, text, duration).also { it.show() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            mainHandler.post(run)
        }
    }

    fun show(context: Context, @StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(resId), duration)
    }

    fun show(
        context: Context,
        @StringRes resId: Int,
        duration: Int = Toast.LENGTH_SHORT,
        vararg formatArgs: Any,
    ) {
        show(context, context.getString(resId, *formatArgs), duration)
    }
}
