package com.measurecam.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * 按 EXIF 方向把照片解码成正立的 Bitmap。
 *
 * CameraX 拍出的 JPEG 像素方向与 EXIF Orientation 可能不一致，不处理会导致
 * 水印落在"转过来"的边上；从相册导入的外部图片更麻烦——手机自拍镜像会写出
 * ORIENTATION_FLIP_HORIZONTAL，部分机型的 TRANSPOSE/TRANSVERSE 也很常见，
 * 只处理 90/180/270 会让镜像的图整个倒过来。这里把 8 种方向全铺平。
 */
object ImageLoader {

    /** 默认上限：超过就按 2 的幂降采样，避免 12MP 原图直接爆内存 */
    const val MAX_DIMENSION = 4096

    fun load(file: File, maxDimension: Int = MAX_DIMENSION): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return decode(
            opener = { runCatching { file.inputStream() }.getOrNull() },
            orientation = {
                runCatching {
                    ExifInterface(file.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            },
            maxDimension = maxDimension
        )
    }

    /** 从相册 Uri 解码。Photo Picker 给的 content:// 只能走流，拿不到路径 */
    fun load(context: Context, uri: Uri, maxDimension: Int = MAX_DIMENSION): Bitmap? {
        val resolver = context.contentResolver
        return decode(
            opener = { runCatching { resolver.openInputStream(uri) }.getOrNull() },
            orientation = {
                runCatching {
                    resolver.openInputStream(uri)?.use { s ->
                        ExifInterface(s).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            },
            maxDimension = maxDimension
        )
    }

    private fun decode(
        opener: () -> InputStream?,
        orientation: () -> Int,
        maxDimension: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        opener()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = opener()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return upright(raw, orientation())
    }

    /**
     * 按 2 的幂降采样，保证**降完**的长边不再超过 [maxDimension]。
     *
     * 注意判断的是"降完之后"，不是"降一半是否还超标"。后者会漏掉一批中间尺寸：
     * 5000×4000 的图算 5000/2=2500 小于上限，于是原样解出来，占 80MB——
     * 相机自己拍的 12MP 没事，从相册导入的单反/高像素照片很容易在这里 OOM。
     */
    internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (maxDimension <= 0 || width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > maxDimension && sample <= 64) {
            sample *= 2
        }
        return sample
    }

    private fun upright(src: Bitmap, orientation: Int): Bitmap {
        val matrix = orientationMatrix(orientation, src.width, src.height) ?: return src
        val out = runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }.getOrNull() ?: return src
        if (out !== src) src.recycle()
        return out
    }

    /**
     * 8 种 EXIF 方向 → 变换矩阵。
     *
     * 旋转绕图片中心，这样 createBitmap 裁出来的就是完整画面；镜像用负缩放、
     * 不指定中心点，交给 createBitmap 自己算变换后的 bounds（这是 AOSP 上
     * 唯一稳的做法，手动补 translate 很容易差半个像素甚至裁掉一条边）。
     */
    internal fun orientationMatrix(orientation: Int, width: Int, height: Int): Matrix? {
        val cx = width / 2f
        val cy = height / 2f
        return when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f, cx, cy) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
                setRotate(90f, cx, cy)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f, cx, cy) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
                setRotate(-90f, cx, cy)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f, cx, cy) }
            else -> null
        }
    }
}
