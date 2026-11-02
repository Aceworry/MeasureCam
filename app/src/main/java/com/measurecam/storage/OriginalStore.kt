package com.measurecam.storage

import android.content.Context
import java.io.File

/**
 * 无水印原图仓库。
 *
 * 为什么要留原图：水印是画进像素的，一旦烧上去就抠不下来。想改数值只能从
 * 没水印的那张重画。代价是每张照片占两份空间，所以这里提供 [trim] 做上限清理，
 * 记录编辑页和删除逻辑都要先跟它对齐。
 *
 * 目录：filesDir/originals/，文件名与成片同名，只在私有空间可见，不进相册。
 */
object OriginalStore {

    private const val DIR = "originals"

    /** 默认总量上限 800MB，超了从最老的开始清 */
    private const val MAX_BYTES = 800L * 1024 * 1024

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun exists(context: Context, name: String?): Boolean =
        !name.isNullOrBlank() && file(context, name).isFile

    fun readable(context: Context, name: String?): File? =
        if (exists(context, name)) file(context, name!!) else null

    /** 把相机刚写出来的缓存文件复制一份留底，原文件由调用方负责删 */
    fun save(context: Context, src: File, name: String): Boolean = runCatching {
        if (!src.isFile) return@runCatching false
        val target = file(context, name)
        val tmp = File(target.absolutePath + ".tmp")
        src.inputStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }
        // 先写临时文件再改名，避免复制到一半被系统杀掉留下半张图
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }.getOrDefault(false)

    fun delete(context: Context, name: String?) {
        if (name.isNullOrBlank()) return
        runCatching { file(context, name).delete() }
    }

    fun totalBytes(context: Context): Long = runCatching {
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    fun count(context: Context): Int = runCatching {
        dir(context).listFiles()?.count { it.isFile } ?: 0
    }.getOrDefault(0)

    /**
     * 超出上限时删最老的，保留 [protect] 里列出的（当前正在编辑的那张不能被清掉）。
     * 返回清掉的文件数。
     */
    fun trim(context: Context, protect: Set<String> = emptySet()): Int {
        val files = runCatching { dir(context).listFiles()?.filter { it.isFile } }.getOrNull()
            ?: return 0
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return 0

        var removed = 0
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= MAX_BYTES) return@forEach
            if (f.name in protect) return@forEach
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                removed++
            }
        }
        return removed
    }

    /** 清空全部原图，记录里会留下失效的 originalName，编辑时会提示重拍 */
    fun clear(context: Context): Int =
        runCatching { dir(context).listFiles()?.count { it.isFile && it.delete() } ?: 0 }
            .getOrDefault(0)
}
