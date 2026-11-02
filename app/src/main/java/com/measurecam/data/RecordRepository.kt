package com.measurecam.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

/**
 * 拍摄记录仓库。一行一条 JSON（JSONL）存在 filesDir/records.jsonl。
 *
 * 没有用数据库：记录量级是每天几十到几百条，使用 AtomicFile 提交完整快照，
 * 而且纯文本能直接导出来排查问题，也不用为了一张表引 Room。
 */
object RecordRepository {

    private const val FILE_NAME = "records.jsonl"

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun append(context: Context, record: CaptureRecord): Boolean = runCatching {
        rewrite(context, load(context) + record)
    }.getOrDefault(false)

    /** 按时间正序返回全部记录 */
    @Synchronized
    fun load(context: Context): List<CaptureRecord> {
        val f = file(context)
        if (!f.exists() && !File(f.path + ".bak").exists()) return emptyList()

        val out = ArrayList<CaptureRecord>()
        AtomicFile(f).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                val obj = runCatching { JsonParser.parseString(t) }.getOrNull()
                if (obj != null && obj.isJsonObject) {
                    runCatching {
                        gson.fromJson(obj, CaptureRecord::class.java).takeIf {
                            !it.id.isNullOrBlank() && !it.photoName.isNullOrBlank()
                        }
                    }.getOrNull()?.let(out::add)
                }
            }
        }
        return out.sortedBy { it.timeMillis }
    }

    fun count(context: Context): Int = load(context).size

    /** 只取最近 N 条，记录页展示用，避免一次解太多 */
    fun recent(context: Context, limit: Int): List<CaptureRecord> =
        load(context).takeLast(limit).reversed()

    /** 按 id 取一条，编辑页用 */
    fun find(context: Context, id: String): CaptureRecord? =
        load(context).firstOrNull { it.id == id }

    /**
     * 整表重写。改一条和删一条都走这里：
     * 由 AtomicFile 管理临时文件、提交和失败回滚，避免先删除旧文件造成丢失。
     */
    @Synchronized
    private fun rewrite(context: Context, list: List<CaptureRecord>): Boolean = runCatching {
        val target = AtomicFile(file(context))
        val stream = target.startWrite()
        try {
            val w = stream.bufferedWriter(Charsets.UTF_8)
            list.sortedBy { it.timeMillis }.forEach { r ->
                w.write(gson.toJson(r))
                w.newLine()
            }
            w.flush()
            target.finishWrite(stream)
            true
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }.getOrDefault(false)

    /** 用 id 匹配替换一条，返回是否找到 */
    @Synchronized
    fun update(context: Context, record: CaptureRecord): Boolean {
        val all = load(context).toMutableList()
        val idx = all.indexOfFirst { it.id == record.id }
        if (idx < 0) return false
        all[idx] = record
        return rewrite(context, all)
    }

    /** 删一条，返回是否删掉 */
    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val all = load(context).toMutableList()
        val removed = all.removeAll { it.id == id }
        if (!removed) return false
        return rewrite(context, all)
    }

    /** 批量删，返回删掉了几条 */
    @Synchronized
    fun deleteAll(context: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val set = ids.toSet()
        val all = load(context).toMutableList()
        val before = all.size
        all.removeAll { it.id in set }
        val removed = before - all.size
        return if (removed > 0 && rewrite(context, all)) removed else 0
    }

    @Synchronized
    fun clear(context: Context) {
        AtomicFile(file(context)).delete()
    }
}
