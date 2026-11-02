package com.measurecam.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.measurecam.data.WatermarkStyle
import com.measurecam.data.CaptureMeta
import com.measurecam.data.CaptureRecord
import com.measurecam.data.CaptureTemplate
import com.measurecam.data.MeasureCategory
import com.measurecam.data.RecordField
import com.measurecam.data.RecordRepository
import com.measurecam.data.SOURCE_IMPORT
import com.measurecam.data.WatermarkAnchor
import com.measurecam.render.WatermarkRenderer
import com.measurecam.util.ImageLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把相册里已有的照片导入成一条「图-数」记录。
 *
 * 现场常常是先用别的相机/微信拍了一堆，回头才想起来要加水印。导入做的事和
 * 拍摄是一样的：留一份无水印原图当基底 → 渲染 → 进相册 → 落记录。区别只有
 * 两点：
 *
 *  1. 数值一律留空。导入时不知道这组数是哪几个测点的，让用户在编辑页补，
 *     所以记录里的字段骨架按分类预置好，只是值都空着。
 *  2. 不写桩号递增基准。这张可能是几天前在别的桩号拍的，拿它当基准会把
 *     整条链带偏。
 *
 * 原图基底是**重新编码过的标准 JPEG**，方向已经拍平、不带 EXIF Orientation。
 * 直接存原始字节也行，但 HEIC/PNG 进来后文件名还是 .jpg，后面解码会踩坑。
 */
class PhotoImporter(private val context: Context) {

    data class Result(
        val imported: Int,
        val failed: Int,
        val records: List<CaptureRecord>
    )

    /**
     * @param stickyValues 常驻字段的当前值，key 是字段 id（存在 SharedPreferences 里）
     * @param onProgress   每处理完一张回调一次，**在后台线程**
     */
    fun import(
        uris: List<Uri>,
        template: CaptureTemplate,
        category: MeasureCategory,
        stickyValues: Map<String, String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result {
        val anchor = template.anchor()
        val wmScale = template.scale()
        val ok = ArrayList<CaptureRecord>()
        var failed = 0

        uris.forEachIndexed { index, uri ->
            val record = runCatching {
                importOne(uri, template, category, stickyValues, anchor, wmScale)
            }.getOrNull()

            if (record == null) failed++ else ok.add(record)
            onProgress(index + 1, uris.size)
        }
        return Result(ok.size, failed, ok)
    }

    private fun importOne(
        uri: Uri,
        template: CaptureTemplate,
        category: MeasureCategory,
        stickyValues: Map<String, String>,
        anchor: WatermarkAnchor,
        wmScale: Float
    ): CaptureRecord? {
        val exif = readExif(uri)
        val takenAt = resolveTakenAt(uri, exif)
        val latLong = exif?.latLong
        val lat = latLong?.getOrNull(0)?.takeIf { !it.isNaN() }
        val lon = latLong?.getOrNull(1)?.takeIf { !it.isNaN() }

        val bitmap = ImageLoader.load(context, uri) ?: return null

        val name = uniqueName(buildName(takenAt))
        val stickyLines = stickyLinesOf(template, stickyValues)
        val meta = CaptureMeta(
            titleLine = template.title,
            stickyLines = stickyLines,
            categoryName = category.name,
            // 导入时没有数值，水印先只有标题/分类/时间/坐标，用户补完再重绘
            measureLines = emptyList(),
            timeMillis = takenAt,
            lat = lat,
            lon = lon,
            address = null,
            showTime = template.showTime,
            showAddress = template.showAddress,
            style = template.watermarkStyle ?: WatermarkStyle(),
            stickyFields = template.activeSticky().map { RecordField(it.label, values = listOf(stickyValue(it.id, it.value, stickyValues)), appearance = it.appearance) }
        )

        // 1) 先留无水印基底。后面用户要改数值，只能从这张重画
        val tmpOriginal = File(context.cacheDir, "imp_src_$name")
        val keptOriginal = runCatching {
            tmpOriginal.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }.getOrDefault(false) && OriginalStore.save(context, tmpOriginal, name)
        runCatching { tmpOriginal.delete() }

        // 2) 渲染成片
        val rendered = WatermarkRenderer.render(bitmap, meta, anchor, wmScale, template.watermarkPosition)
        val tmpOut = File(context.cacheDir, "imp_out_$name")
        val compressed = runCatching {
            tmpOut.outputStream().use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }.getOrDefault(false)

        if (compressed) writeExif(tmpOut, meta)
        val saved = compressed && PhotoSaver.save(context, tmpOut, name)

        runCatching { tmpOut.delete() }
        if (rendered !== bitmap) runCatching { rendered.recycle() }
        runCatching { bitmap.recycle() }

        if (!saved) {
            // 成片没写进相册，基底也别留，否则 originals 里会攒一堆孤儿
            if (keptOriginal) OriginalStore.delete(context, name)
            return null
        }

        // 3) 落记录。字段骨架按分类预置、值全空，用户进编辑页逐个填
        val stickyFields = template.activeSticky().map { f ->
            RecordField(
                label = f.label,
                unit = "",
                values = listOf(stickyValue(f.id, f.value, stickyValues)),
                appearance = f.appearance
            )
        }
        val measureFields = category.activeFields().map { f ->
            RecordField(
                label = f.label,
                unit = f.unit,
                values = List(f.slots()) { "" },
                role = f.role.name,
                type = f.type.name,
                warningRange = f.warningRange,
                appearance = f.appearance
            )
        }

        val record = CaptureRecord(
            timeMillis = takenAt,
            templateId = template.id,
            templateName = template.name,
            categoryName = category.name,
            photoName = name,
            source = SOURCE_IMPORT,
            originalName = if (keptOriginal) name else null,
            lat = lat,
            lon = lon,
            address = null,
            chainage = "",
            chainageMeters = null,
            moveMeters = null,
            watermarkAnchor = anchor.key,
            watermarkPosition = template.watermarkPosition,
            watermarkScale = wmScale,
            titleLine = template.title,
            showTime = template.showTime,
            showAddress = template.showAddress,
            sticky = stickyFields,
            fields = measureFields,
            watermarkStyle = template.watermarkStyle
        )
        if (!RecordRepository.append(context, record)) {
            PhotoSaver.deleteByName(context, name)
            OriginalStore.delete(context, name)
            return null
        }
        OriginalStore.trim(context, setOf(name))
        return record
    }

    // ---------------- EXIF / 时间 ----------------

    private fun readExif(uri: Uri): ExifInterface? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    }.getOrNull()

    /**
     * 拍摄时间：EXIF 原始时间 > MediaStore 的 DATE_TAKEN > 文件修改时间 > 现在。
     * 顺序不能反——微信转发的图 DATE_TAKEN 常常是转发时间，EXIF 才是真拍的时刻。
     */
    private fun resolveTakenAt(uri: Uri, exif: ExifInterface?): Long {
        parseExifTime(
            runCatching {
                exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
            }.getOrNull()
        )?.let { return it }

        runCatching {
            val projection = arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val taken = c.getLong(0)
                if (taken > 0L) taken else c.getLong(1) * 1000L
            }
        }.getOrNull()?.takeIf { it > 0L }?.let { return it }

        return System.currentTimeMillis()
    }

    private fun parseExifTime(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text)?.time
        }.getOrNull()
    }

    // ---------------- 命名 ----------------

    private fun buildName(takenAt: Long): String =
        "MC_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(takenAt)) + ".jpg"

    /**
     * 重名会让后续"覆盖相册里那张"失效：MediaStore 遇到同名会自己改成 "xxx (1).jpg"，
     * 而 findUri 按 DISPLAY_NAME 精确查，之后就永远找不到、编辑保存会另插一张。
     * 所以入库前先确保这个名字是干净的。
     */
    private fun uniqueName(base: String): String {
        val stem = base.removeSuffix(".jpg")
        var candidate = base
        var i = 2
        while (i < 1000 &&
            (OriginalStore.exists(context, candidate) ||
                PhotoSaver.findUri(context, candidate) != null)
        ) {
            candidate = "${stem}_$i.jpg"
            i++
        }
        return candidate
    }

    // ---------------- 常驻字段 ----------------

    private fun stickyValue(fieldId: String, fallback: String, values: Map<String, String>): String =
        (values[fieldId] ?: fallback).trim()

    private fun stickyLinesOf(
        template: CaptureTemplate,
        values: Map<String, String>
    ): List<String> = template.activeSticky().mapNotNull { f ->
        val v = stickyValue(f.id, f.value, values)
        if (v.isEmpty()) null else "${f.label}: $v"
    }

    private fun writeExif(file: File, meta: CaptureMeta) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(meta.timeMillis))
            )
            if (meta.lat != null && meta.lon != null) {
                exif.setLatLong(meta.lat, meta.lon)
            }
            // 像素已经拍平，方向标记必须归零，否则看图软件会再转一次
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
        }
    }
}
