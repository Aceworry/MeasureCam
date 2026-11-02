package com.measurecam

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.measurecam.databinding.ActivityPhotoPreviewBinding
import com.measurecam.util.ImageLoader
import java.util.concurrent.Executors

/** 只读预览选择器授权的单张照片。返回后继续拍摄。 */
class PhotoPreviewActivity : AppCompatActivity() {
    companion object {
        fun open(context: Context, uri: Uri) {
            context.startActivity(Intent(context, PhotoPreviewActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private val reader = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPhotoPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val uri = intent.data
        val metrics = resources.displayMetrics
        val limit = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(720, 2048)
        reader.execute {
            val image = try {
                uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }?.let { ImageLoader.load(this, it, limit) }
            } catch (_: Exception) {
                null // 文件在选中后被移除，或云端提供方暂时不可读。
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    image?.recycle()
                } else {
                    binding.loading.visibility = View.GONE
                    if (image == null) binding.message.visibility = View.VISIBLE
                    else binding.photo.setImageBitmap(image)
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        reader.shutdownNow()
        super.onDestroy()
    }
}
