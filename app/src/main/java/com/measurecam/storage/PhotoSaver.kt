package com.measurecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 写入系统相册。Android 10+ 走 MediaStore 分区存储，9 及以下走传统目录。
 */
object PhotoSaver {

    private const val ALBUM = "图数相机"

    /**
     * [jpeg] 是已经渲染好水印、写好 EXIF 的成品文件。
     * 返回 true 表示已成功进入相册。
     */
    fun save(context: Context, jpeg: File, displayName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, jpeg, displayName)
        } else {
            saveLegacy(context, jpeg, displayName)
        }
    }.getOrDefault(false)

    private fun saveScoped(context: Context, jpeg: File, displayName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                jpeg.inputStream().use { it.copyTo(out) }
            } != null
        }.getOrDefault(false)

        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    private fun saveLegacy(context: Context, jpeg: File, displayName: String): Boolean {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        )
        if (!dir.exists() && !dir.mkdirs()) return false

        val target = File(dir, displayName)
        jpeg.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null
        )
        return true
    }

    // ---------------- 改写与删除（记录编辑用）----------------

    /** 按文件名在相册里定位那张图 */
    fun findUri(context: Context, displayName: String): Uri? = runCatching {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val pathValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${Environment.DIRECTORY_PICTURES}/$ALBUM/" else {
                @Suppress("DEPRECATION")
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "$ALBUM/$displayName").absolutePath
            }
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND $pathColumn = ?"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf(displayName, pathValue), null
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                )
            } else {
                null
            }
        }
    }.getOrNull()

    /**
     * 用新的 jpeg 覆盖相册里同名的那张。
     *
     * 文件名保持不变，这样记录不用改 photoName；相册的缩略图缓存靠
     * DATE_MODIFIED 变脏来触发刷新。找不到原图就退回新建一张。
     */
    fun overwrite(context: Context, jpeg: File, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return runCatching { saveLegacy(context, jpeg, displayName) }.getOrDefault(false)
        }

        val uri = findUri(context, displayName)
        if (uri == null) return runCatching { saveScoped(context, jpeg, displayName) }.getOrDefault(false)

        val ok = runCatching {
            // "wt" = write + truncate。必须截断，否则新图比旧图小时尾部会留着旧数据
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                jpeg.inputStream().use { it.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!ok) return false

        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.SIZE, jpeg.length())
            }
            context.contentResolver.update(uri, values, null, null)
        }
        return true
    }

    /** 从相册删掉这张。本来就不在也算成功 */
    fun deleteByName(context: Context, displayName: String): Boolean {
        val uri = findUri(context, displayName) ?: return true
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }
}
