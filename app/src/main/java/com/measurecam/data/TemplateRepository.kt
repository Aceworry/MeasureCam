package com.measurecam.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 桩号递增的现场状态。按模板存：同一套模板下所有分类共用一条桩号线，
 * 因为在同一个测点上你可能先拍基平台、再拍顶平台，桩号不该跟着跳。
 */
data class ChainageState(
    val meters: Double = 0.0,
    val lat: Double? = null,
    val lon: Double? = null,
    val timeMillis: Long = 0L
)

/**
 * 模板持久化。存 filesDir/templates.json，纯文本，方便导出/备份/手改。
 */
object TemplateRepository {

    private const val FILE_NAME = "templates.json"
    private const val PREF = "measurecam"
    private const val KEY_SELECTED = "selected_template_id"
    private const val KEY_LAST_VALUES = "last_measure_values"
    private const val KEY_CHAINAGE = "chainage_state"
    private const val KEY_CATEGORY = "selected_category"

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var cache: MutableList<CaptureTemplate>? = null

    @Synchronized
    fun load(context: Context): MutableList<CaptureTemplate> {
        cache?.let { return it }

        val file = File(context.filesDir, FILE_NAME)
        val list: MutableList<CaptureTemplate> = if (file.exists() || File(file.path + ".bak").exists()) {
            runCatching {
                val raw = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
                parseTemplates(raw)
            }.getOrElse { mutableListOf() }
        } else {
            mutableListOf()
        }

        if (list.isEmpty()) {
            list.add(defaultTemplate())
        } else if (list.none { it.activeCategories().isNotEmpty() }) {
            // 模板都在但没有一个分类可用，补一个默认模板兜底，避免进去无法拍照
            list.add(defaultTemplate())
        }
        cache = list
        persist(context, list)
        return list
    }

    /**
     * v1 的模板是「常驻 + 一维字段列表」，v2 拆成了分类。
     * 这里在 JSON 层做一次迁移：老数据里没有 categories 的，
     * 把 measureFields 整体装进一个叫「默认分类」的分类里。
     * 这样老用户升级后模板不会丢字段。
     */
    private fun parseTemplates(raw: String): MutableList<CaptureTemplate> {
        if (raw.isBlank()) return mutableListOf()

        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
        if (root == null || !root.isJsonArray()) return mutableListOf()

        val fixed = JsonArray()
        root.asJsonArray.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val obj = el.asJsonObject

            val cats = obj.get("categories")
            val legacyFields = obj.get("measureFields")

            val hasCats = cats != null && cats.isJsonArray && cats.asJsonArray.size() > 0
            if (!hasCats) {
                val newCats = JsonArray()
                if (legacyFields != null && legacyFields.isJsonArray && legacyFields.asJsonArray.size() > 0) {
                    val c = JsonObject()
                    c.addProperty("id", java.util.UUID.randomUUID().toString())
                    c.addProperty("name", "默认分类")
                    c.addProperty("enabled", true)
                    c.add("fields", legacyFields.deepCopy())
                    newCats.add(c)
                }
                obj.add("categories", newCats)
                obj.remove("measureFields")
            }

            // sticky 缺失时补空数组，避免反序列化出 null
            val sticky = obj.get("sticky")
            if (sticky == null || !sticky.isJsonArray) obj.add("sticky", JsonArray())

            fixed.add(obj)
        }

        val type = object : TypeToken<List<CaptureTemplate>>() {}.type
        val parsed: List<CaptureTemplate>? = runCatching {
            gson.fromJson<List<CaptureTemplate>>(fixed, type)
        }.getOrNull()

        return parsed?.map { normalize(it) }?.toMutableList() ?: mutableListOf()
    }

    /** Gson 可能把缺失的列表字段填成 null，统一修一遍 */
    private fun normalize(t: CaptureTemplate): CaptureTemplate {
        t.stickyList()
        t.categoryList().forEach { it.fieldList() }
        return t
    }

    @Synchronized
    fun save(context: Context, list: MutableList<CaptureTemplate>): Boolean {
        if (!persist(context, list)) return false
        cache = list
        return true
    }

    private fun persist(context: Context, list: List<CaptureTemplate>): Boolean = runCatching {
        val target = AtomicFile(File(context.filesDir, FILE_NAME))
        val stream = target.startWrite()
        try {
            stream.write(gson.toJson(list).toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            true
        } catch (e: Exception) { target.failWrite(stream); throw e }
    }.getOrDefault(false)

    fun selectedTemplate(context: Context): CaptureTemplate {
        val list = load(context)
        val id = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun select(context: Context, templateId: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, templateId).apply()
    }

    // ---- 当前分类：按模板记，切换模板后各自记住上次用的分类 ----

    fun selectedCategory(context: Context, templateId: String): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("$KEY_CATEGORY:$templateId", null)

    fun selectCategory(context: Context, templateId: String, categoryId: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("$KEY_CATEGORY:$templateId", categoryId).apply()
    }

    // ---- 沿用的上次数值：连续测点时能省掉大部分输入 ----

    fun lastMeasureValues(context: Context, templateId: String): Map<String, String> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("$KEY_LAST_VALUES:$templateId", null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val parsed: Map<String, String>? = gson.fromJson(raw, type)
            parsed ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    fun rememberMeasureValues(context: Context, templateId: String, values: Map<String, String>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString("$KEY_LAST_VALUES:$templateId", gson.toJson(values))
            .apply()
    }

    // ---- 桩号递增状态 ----

    fun chainageState(context: Context, templateId: String): ChainageState? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("$KEY_CHAINAGE:$templateId", null) ?: return null
        return runCatching { gson.fromJson(raw, ChainageState::class.java) }.getOrNull()
    }

    fun rememberChainage(context: Context, templateId: String, state: ChainageState) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString("$KEY_CHAINAGE:$templateId", gson.toJson(state))
            .apply()
    }

    fun clearChainage(context: Context, templateId: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove("$KEY_CHAINAGE:$templateId").apply()
    }

    // ---- 默认模板 ----

    private fun chainageField() = FieldDef(
        label = "测点桩号",
        unit = "",
        type = FieldType.TEXT,
        role = FieldRole.CHAINAGE
    )

    private fun flatness(axis: String) = FieldDef(
        label = "平整度（$axis）",
        unit = "mm",
        type = FieldType.DECIMAL,
        valueCount = 2
    )

    /**
     * 工程测量默认模板，按现场实际的五个部位预置好：
     * 基平台 / 顶平台 / 坡面 / 钢丝网格 / 排水沟·截流沟。
     * 第一次打开就能直接拍，不用先配模板。
     */
    fun defaultTemplate(): CaptureTemplate = CaptureTemplate(
        name = "航道整治",
        title = "",
        sticky = mutableListOf(
            StickyField(label = "项目名称"),
            StickyField(label = "标段"),
            StickyField(label = "测量区域")
        ),
        categories = mutableListOf(
            MeasureCategory(
                name = "基平台",
                fields = mutableListOf(
                    chainageField(),
                    FieldDef(label = "深度", unit = "mm", type = FieldType.INTEGER),
                    flatness("水平"),
                    flatness("垂直"),
                    FieldDef(label = "宽度", unit = "mm", type = FieldType.INTEGER)
                )
            ),
            MeasureCategory(
                name = "顶平台",
                fields = mutableListOf(
                    chainageField(),
                    FieldDef(label = "深度", unit = "mm", type = FieldType.INTEGER),
                    flatness("水平"),
                    flatness("垂直"),
                    FieldDef(label = "宽度", unit = "mm", type = FieldType.INTEGER)
                )
            ),
            MeasureCategory(
                name = "坡面",
                fields = mutableListOf(
                    chainageField(),
                    FieldDef(label = "深度", unit = "mm", type = FieldType.INTEGER),
                    flatness("水平"),
                    flatness("垂直")
                )
            ),
            MeasureCategory(
                name = "钢丝网格",
                fields = mutableListOf(
                    FieldDef(label = "砂垫层", unit = "cm", type = FieldType.DECIMAL),
                    FieldDef(label = "网格铺石层", unit = "cm", type = FieldType.DECIMAL)
                )
            ),
            MeasureCategory(
                name = "排水沟/截流沟",
                fields = mutableListOf(
                    chainageField(),
                    FieldDef(label = "上口宽", unit = "mm", type = FieldType.INTEGER),
                    FieldDef(label = "左侧深", unit = "mm", type = FieldType.INTEGER),
                    FieldDef(label = "右侧深", unit = "mm", type = FieldType.INTEGER),
                    FieldDef(label = "下底宽", unit = "mm", type = FieldType.INTEGER)
                )
            )
        )
    )
}
