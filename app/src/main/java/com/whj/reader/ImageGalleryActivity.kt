package com.whj.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.whj.reader.databinding.ActivityImageGalleryBinding
import com.whj.reader.util.Toasts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏看图：缩放/平移、侧点或左右滑（惯性）切换、按需解码。
 * 退出时 [RESULT_PARA_INDEX] 为当前图对应阅读段索引。
 */
class ImageGalleryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_PARA_INDICES = "para_indices"
        const val EXTRA_START = "start"
        const val RESULT_PARA_INDEX = "para_index"

        fun intent(
            context: Context,
            paths: ArrayList<String>,
            paraIndices: IntArray,
            startIndex: Int,
        ): Intent =
            Intent(context, ImageGalleryActivity::class.java)
                .putStringArrayListExtra(EXTRA_PATHS, paths)
                .putExtra(EXTRA_PARA_INDICES, paraIndices)
                .putExtra(EXTRA_START, startIndex)
    }

    private lateinit var binding: ActivityImageGalleryBinding
    private var paths: List<String> = emptyList()
    private var paraIndices: IntArray = intArrayOf()
    private var index: Int = 0
    private var loadJob: Job? = null
    private var chromeVisible = true

    private val bitmapCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 * 1024 * 1024, 48 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityImageGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersive()

        paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        paraIndices = intent.getIntArrayExtra(EXTRA_PARA_INDICES) ?: IntArray(paths.size) { it }
        index = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, (paths.size - 1).coerceAtLeast(0))

        if (paths.isEmpty()) {
            Toasts.show(this, R.string.image_gallery_empty)
            finish()
            return
        }

        binding.btnClose.setOnClickListener { finishWithResult() }
        binding.imageView.onSideTap = { zone ->
            when (zone) {
                0 -> go(-1)
                2 -> go(+1)
            }
        }
        binding.imageView.onSwipePage = { forward -> go(if (forward) +1 else -1) }
        binding.imageView.onCenterTap = { toggleChrome() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishWithResult()
                }
            },
        )

        showIndex(index)
    }

    private fun go(delta: Int) {
        val next = index + delta
        if (next !in paths.indices) {
            Toasts.show(
                this,
                if (delta > 0) R.string.image_gallery_last else R.string.image_gallery_first,
            )
            return
        }
        showIndex(next)
    }

    private fun showIndex(i: Int) {
        index = i
        updateChrome()
        val path = paths[i]
        val cached = bitmapCache.get(path)
        if (cached != null && !cached.isRecycled) {
            binding.imageView.setImageBitmap(cached)
            binding.progress.visibility = View.GONE
            preloadNeighbors(i)
            return
        }
        binding.progress.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                decodeSampled(path, maxSide())
            }
            if (isFinishing || isDestroyed) {
                bmp?.recycle()
                return@launch
            }
            if (index != i) {
                // 已翻走：缓存即可
                if (bmp != null) bitmapCache.put(path, bmp)
                return@launch
            }
            if (bmp == null) {
                binding.progress.visibility = View.GONE
                binding.imageView.setImageBitmap(null)
                Toasts.show(this@ImageGalleryActivity, R.string.image_gallery_load_fail)
                return@launch
            }
            bitmapCache.put(path, bmp)
            binding.imageView.setImageBitmap(bmp)
            binding.progress.visibility = View.GONE
            preloadNeighbors(i)
        }
    }

    private fun preloadNeighbors(i: Int) {
        val targets = listOf(i - 1, i + 1, i + 2).filter { it in paths.indices }
        lifecycleScope.launch(Dispatchers.IO) {
            for (ti in targets) {
                val p = paths[ti]
                if (bitmapCache.get(p) != null) continue
                val bmp = decodeSampled(p, maxSide()) ?: continue
                bitmapCache.put(p, bmp)
            }
        }
    }

    private fun maxSide(): Int {
        val dm = resources.displayMetrics
        // 解码边长：屏长边 * 2，便于双击放大仍清晰
        return (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceAtLeast(1080)
    }

    private fun decodeSampled(path: String, maxSide: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longSide / sample > maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }

    private fun updateChrome() {
        binding.tvIndex.text = getString(
            R.string.image_gallery_index,
            index + 1,
            paths.size,
        )
        binding.topBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        updateChrome()
        if (chromeVisible) {
            // 短暂显示系统栏提示可退出
            exitImmersive()
        } else {
            enterImmersive()
        }
    }

    private fun enterImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitImmersive() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun finishWithResult() {
        val para = paraIndices.getOrNull(index) ?: -1
        setResult(
            RESULT_OK,
            Intent().putExtra(RESULT_PARA_INDEX, para),
        )
        finish()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        binding.imageView.setImageBitmap(null)
        bitmapCache.evictAll()
        super.onDestroy()
    }
}
