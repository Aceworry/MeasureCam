package com.measurecam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.measurecam.data.CaptureRecord
import com.measurecam.data.RecordField
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把拍摄记录导成 CSV。
 *
 * 两个去向：
 *  1. 应用外部目录（export/），用于分享（FileProvider 给 content://）
 *  2. 公共「下载/图数相机」目录，方便用数据线或文件管理器直接取走
 *
 * 带 UTF-8 BOM：不带的话 Excel 打开中文会变乱码，这是国内用 CSV 最常见的问题。
 */
object CsvExporter {

    private const val BOM = "\uFEFF"

    data class Result(
        val ok: Boolean,
        val count: Int,
        val fileName: String,
        val shareUri: Uri? = null,
        val publicPath: String? = null,
        val mime: String = "text/csv",
        val photoCount: Int = 0,
        val missing: Int = 0
    )

    private data class Col(
        val key: String,
        val label: String,
        val unit: String,
        val count: Int
    )

    fun export(context: Context, records: List<CaptureRecord>, withPhotos: Boolean = false): Result {
        val suffix = if (withPhotos) "zip" else "csv"
        val mime = if (withPhotos) "application/zip" else "text/csv"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "图数记录_${stamp}_${UUID.randomUUID().toString().take(8)}.$suffix"
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "export")
        dir.mkdirs()
        val file = File(dir, name)
        val partial = File(dir, "$name.part")
        var counts = 0 to 0
        return try {
            require(records.isNotEmpty())
            partial.outputStream().buffered().use { output ->
                if (withPhotos) {
                    counts = writeBundle(output, records) { photo ->
                        PhotoSaver.findUri(context, photo)?.let { uri ->
                            try { context.contentResolver.openInputStream(uri) }
                            catch (_: java.io.FileNotFoundException) { null }
                        }
                    }
                } else {
                    output.write((BOM + build(records)).toByteArray(Charsets.UTF_8))
                }
            }
            check(partial.renameTo(file))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val path = runCatching { publishToDownloads(context, file, mime) }.getOrNull()
            Result(true, records.size, name, uri, path, mime, counts.first, counts.second)
        } catch (_: Exception) {
            file.delete()
            Result(false, 0, name)
        } finally { partial.delete() }
    }

    /** 每个成片只复制一次。缺图时仍保留该条 CSV 记录，并在包内列出原文件名。 */
    internal fun writeBundle(output: OutputStream, records: List<CaptureRecord>, openPhoto: (String) -> InputStream?): Pair<Int, Int> {
        val included = mutableSetOf<String>()
        val missing = mutableListOf<String>()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            for (name in records.map { it.photoName }.distinct()) {
                val safe = name.isNotBlank() && name != "." && name != ".." &&
                    name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
                val photo = if (safe) openPhoto(name) else null
                if (photo == null) { missing.add(name); continue }
                photo.use {
                    zip.putNextEntry(ZipEntry("照片/$name"))
                    check(it.copyTo(zip) > 0) { "照片为空：$name" }
                    zip.closeEntry()
                }
                included.add(name)
            }
            fun textEntry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val csvRows = records.map { if (it.photoName in included) it.copy(photoName = "照片/${it.photoName}") else it }
            textEntry("记录.csv", BOM + build(csvRows))
            if (missing.isNotEmpty()) textEntry("缺失照片.txt", missing.joinToString("\r\n"))
        }
        return included.size to missing.size
    }

    private fun publishToDownloads(context: Context, file: File, mime: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/图数相机")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "${Environment.DIRECTORY_DOWNLOADS}/图数相机/${file.name}"
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "图数相机")
        dir.mkdirs()
        val target = File(dir, file.name)
        val partial = File(dir, file.name + ".part")
        try {
            file.copyTo(partial)
            check(partial.renameTo(target))
        } finally { partial.delete() }
        return target.absolutePath
    }

    // ---------------- CSV 组装 ----------------

    internal fun build(records: List<CaptureRecord>): String {
        val stickyCols = LinkedHashMap<String, String>()   // label -> label
        val fieldCols = LinkedHashMap<String, Col>()       // label|unit -> Col

        records.forEach { r ->
            r.stickyList().forEach { f ->
                if (f.label.isNotBlank()) stickyCols.getOrPut(f.label) { f.label }
            }
            r.fieldList().forEach field@ { f ->
                if (f.label.isBlank()) return@field
                // 桩号已经单独成列，不再重复
                if (f.isChainage() || (f.role == null && f.label.contains("桩号"))) return@field
                val key = "${f.label}|${f.unit}"
                val existing = fieldCols[key]
                val n = f.valueList().size.coerceAtLeast(1)
                if (existing == null) {
                    fieldCols[key] = Col(key, f.label, f.unit, n)
                } else if (n > existing.count) {
                    fieldCols[key] = existing.copy(count = n)
                }
            }
        }

        val header = ArrayList<String>()
        header.add("序号")
        header.add("拍摄时间")
        header.add("模板")
        header.add("分类")
        header.add("桩号")
        header.add("桩号(米)")
        header.add("位移(米)")
        header.add("GPS纬度")
        header.add("GPS经度")
        header.add("定位地址")
        header.add("照片文件")
        // 后补导入的和现场拍的混在一张表里，得能分清
        header.add("来源")
        header.add("天气")
        stickyCols.values.forEach { header.add(it) }
        fieldCols.values.forEach { c ->
            if (c.count <= 1) {
                header.add(withUnit(c.label, c.unit))
            } else {
                for (i in 1..c.count) header.add(withUnit("${c.label}$i", c.unit))
            }
        }

        val sb = StringBuilder()
        sb.append(header.joinToString(",") { esc(it) }).append("\r\n")

        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        records.forEachIndexed { index, r ->
            val row = ArrayList<String>()
            row.add((index + 1).toString())
            row.add(timeFmt.format(Date(r.timeMillis)))
            row.add(r.templateName)
            row.add(r.categoryName)
            row.add(r.chainage)
            row.add(r.chainageMeters?.let { fmt1(it) } ?: "")
            row.add(r.moveMeters?.let { fmt1(it) } ?: "")
            row.add(r.lat?.let { String.format(Locale.US, "%.7f", it) } ?: "")
            row.add(r.lon?.let { String.format(Locale.US, "%.7f", it) } ?: "")
            row.add(r.address.orEmpty())
            row.add(r.photoName)
            row.add(if (r.imported()) "导入" else "拍摄")
            row.add(r.weather.orEmpty())

            val stickyMap = r.stickyList().associateBy { it.label }
            stickyCols.values.forEach { label ->
                row.add(stickyMap[label]?.valueList()?.firstOrNull().orEmpty())
            }

            val fieldMap = r.fieldList().associateBy { "${it.label}|${it.unit}" }
            fieldCols.values.forEach { c ->
                val v = fieldMap[c.key]?.valueList() ?: emptyList()
                if (c.count <= 1) {
                    row.add(v.firstOrNull().orEmpty())
                } else {
                    for (i in 0 until c.count) row.add(v.getOrElse(i) { "" })
                }
            }

            sb.append(row.joinToString(",") { esc(it) }).append("\r\n")
        }

        return sb.toString()
    }

    private fun withUnit(label: String, unit: String) =
        if (unit.isBlank()) label else "$label($unit)"

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)

    /** CSV 转义：有逗号、引号、换行就整体加引号，内部引号双写 */
    private fun esc(s: String): String {
        if (s.isEmpty()) return ""
        val need = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
        return if (need) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /** 记录页摘要用：把一条记录的数值压成一行文本 */
    fun summaryLine(r: CaptureRecord): String {
        val parts = ArrayList<String>()
        r.fieldList().forEach { f ->
            if (f.isChainage() || (f.role == null && f.label.contains("桩号"))) return@forEach
            val v = f.valueList().filter { it.isNotBlank() }
            if (v.isEmpty()) return@forEach
            parts.add("${f.label} ${v.joinToString("/")}")
        }
        return parts.joinToString("  ")
    }

    fun fieldOf(r: CaptureRecord, label: String): String? =
        r.fieldList().firstOrNull { it.label == label }?.valueList()?.firstOrNull()
}
