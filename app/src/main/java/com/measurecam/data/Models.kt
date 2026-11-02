package com.measurecam.data

import java.util.UUID

/** 水印在可移动距离上的比例，(0,0) 为左上，(1,1) 为右下。 */
data class WatermarkPosition(val x: Float, val y: Float) {
    fun bounded() = WatermarkPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    companion object {
        fun fromAnchor(anchor: WatermarkAnchor) = WatermarkPosition(anchor.col / 2f, anchor.row / 2f)
    }
}

/** 可移动区域的起点及行程。同一比例可映射到预览和不同分辨率的成片。 */
data class WatermarkArea(val left: Float, val top: Float, val travelX: Float, val travelY: Float) {
    fun positionAt(x: Float, y: Float) = WatermarkPosition(
        if (travelX > 0) (x - left) / travelX else 0f,
        if (travelY > 0) (y - top) / travelY else 0f
    ).bounded()
    fun x(position: WatermarkPosition) = left + position.x * travelX
    fun y(position: WatermarkPosition) = top + position.y * travelY
}

data class ReadingRange(val min: Double? = null, val max: Double? = null)

/** 本机拍摄 */
const val SOURCE_CAMERA = "camera"

/** 从系统相册导入。导入的照片不参与桩号递增，列表里也单独标一下 */
const val SOURCE_IMPORT = "import"

/**
 * 水印在成片上的落点，九宫格。
 *
 * 工程照片各有各的构图：拍坡面时下面往往是护脚，水印压左下会挡住坡脚；
 * 拍排水沟时人蹲在坑边，上部是大面积地面，水印放上面更清楚。
 * 所以位置交给每张照片自己定，模板里存的是默认值。
 */
enum class WatermarkAnchor(val key: String) {
    TOP_LEFT("tl"),
    TOP_CENTER("tc"),
    TOP_RIGHT("tr"),
    MIDDLE_LEFT("ml"),
    MIDDLE_CENTER("mc"),
    MIDDLE_RIGHT("mr"),
    BOTTOM_LEFT("bl"),
    BOTTOM_CENTER("bc"),
    BOTTOM_RIGHT("br");

    val row: Int get() = ordinal / 3       // 0 上 1 中 2 下
    val col: Int get() = ordinal % 3       // 0 左 1 中 2 右

    companion object {
        /** 老记录里没有这个键，一律回落到左下角（v1.2 的固定位置） */
        fun fromKey(key: String?): WatermarkAnchor =
            entries.firstOrNull { it.key == key } ?: BOTTOM_LEFT

        /** 从「行,列」还原，编辑页九宫格点击用 */
        fun of(row: Int, col: Int): WatermarkAnchor =
            entries[(row.coerceIn(0, 2) * 3 + col.coerceIn(0, 2))]
    }
}

/**
 * 数值字段的输入类型。决定速录面板弹哪种键盘。
 */
enum class FieldType {
    DECIMAL, // 小数，弹带小数点的数字键盘
    INTEGER, // 整数，纯数字键盘
    TEXT     // 自由文本（桩号这类就属于 TEXT）
}

/**
 * 字段的角色。多数字段是普通数值，桩号需要特殊对待：
 * 它要参与「按 GPS 位移自动递增」的推算，所以要能把它从一堆字段里认出来。
 */
enum class FieldRole {
    NONE,
    CHAINAGE // 测点编号 / 桩号，如 K0+350
}

/**
 * 速录层字段：「标签 + 数值 + 单位」。
 *
 * [valueCount] 用于平整度这类一个标签下要记多个数的字段
 * （工程上平整度每个方向记 2 个读数）。多个值在表单里排成一行，
 * 存储时用 "/" 连接，水印上渲染成「平整度（水平）: 3.0 / 3.2 mm」。
 */
data class FieldDef(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val unit: String = "",
    val type: FieldType = FieldType.DECIMAL,
    val defaultValue: String = "",
    val enabled: Boolean = true,
    val valueCount: Int = 1,
    val role: FieldRole = FieldRole.NONE,
    val appearance: FieldAppearance? = null,
    val warningRange: ReadingRange? = null
) {
    /** 实际生效的输入格数，至少 1 */
    fun slots(): Int = valueCount.coerceIn(1, 6)

    /** 把 "3.0/3.2" 拆成 ["3.0", "3.2"]，长度对齐 slots */
    fun splitValue(value: String): List<String> {
        val n = slots()
        if (n == 1) return listOf(value)
        val parts = value.split("/").map { it.trim() }
        return (0 until n).map { parts.getOrElse(it) { "" } }
    }

    /** 把多格值合回存储用的字符串 */
    fun joinValues(values: List<String>): String =
        if (slots() == 1) values.firstOrNull().orEmpty().trim()
        else values.joinToString("/") { it.trim() }

    /**
     * 渲染成水印上的一行，例如「深度: 1000 mm」「平整度（水平）: 3.0 / 3.2 mm」。
     * 空值返回空串，调用方负责丢弃。
     */
    fun format(value: String): String {
        val parts = splitValue(value).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val body = if (slots() == 1) parts.first() else parts.joinToString(" / ")
        return if (unit.isBlank()) "$label: $body" else "$label: $body $unit"
    }

    /** 水印里的短标签，避免行太长 */
    fun shortLabel(): String = label.ifBlank { "值" }

    fun isChainage(): Boolean = role == FieldRole.CHAINAGE
}

/**
 * 常驻层字段：项目名称、标段、测量区域这类。
 * 在取景页顶部就能改，不用进任何编辑页。
 */
data class StickyField(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    var value: String = "",
    val enabled: Boolean = true,
    val appearance: FieldAppearance? = null
)

/**
 * 数值分类。一套模板下可以有多个分类（基平台、顶平台、坡面、钢丝网格、排水沟），
 * 每个分类有自己的一组字段。拍之前先选分类，速录面板只显示该分类的字段。
 */
data class MeasureCategory(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    /**
     * 声明成可空是为了扛住 Gson：它走 Unsafe 分配对象、不执行构造函数，
     * 老 JSON 里缺这个键时字段会是 null 而不是空列表。统一用 [fieldList] 访问。
     */
    var fields: MutableList<FieldDef>? = mutableListOf(),
    var enabled: Boolean = true
) {
    fun fieldList(): MutableList<FieldDef> =
        fields ?: mutableListOf<FieldDef>().also { fields = it }

    fun activeFields(): List<FieldDef> =
        fieldList().filter { it.enabled && it.label.isNotBlank() }

    fun chainageField(): FieldDef? = activeFields().firstOrNull { it.isChainage() }
}

/**
 * 一个模板 = 常驻层 + 若干数值分类 + 系统层开关 + 桩号递增参数。
 */
data class CaptureTemplate(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "工程测量",
    var title: String = "",
    var sticky: MutableList<StickyField>? = mutableListOf(),
    var categories: MutableList<MeasureCategory>? = mutableListOf(),
    var showTime: Boolean = true,
    var showLocation: Boolean = false,
    var showAddress: Boolean = true,
    /** 桩号是否按 GPS 位移自动递增 */
    var autoChainage: Boolean = true,
    /** 拿不到有效 GPS 位移时按这个步长递增（米） */
    var chainageStep: Double = 20.0,
    /** 水印默认落点，拍的时候可以临时改，改完记在这张照片上 */
    var watermarkAnchor: String = WatermarkAnchor.BOTTOM_LEFT.key,
    /** 水印字号缩放，1.0 = 基准 */
    var watermarkScale: Float = 1.0f,
    var watermarkStyle: WatermarkStyle? = null,
    var showWeather: Boolean = false,
    var maxChainageAdvance: Int? = null,
    var watermarkPosition: WatermarkPosition? = null
) {
    fun advanceLimit(): Int = maxChainageAdvance?.takeIf { it > 0 } ?: 50

    fun anchor(): WatermarkAnchor = WatermarkAnchor.fromKey(watermarkAnchor)

    fun scale(): Float = watermarkScale.coerceIn(0.7f, 1.8f)

    fun stickyList(): MutableList<StickyField> =
        sticky ?: mutableListOf<StickyField>().also { sticky = it }

    fun categoryList(): MutableList<MeasureCategory> =
        categories ?: mutableListOf<MeasureCategory>().also { categories = it }

    fun activeSticky(): List<StickyField> =
        stickyList().filter { it.enabled && it.label.isNotBlank() }

    fun activeCategories(): List<MeasureCategory> =
        categoryList().filter { it.enabled && it.name.isNotBlank() }

    fun category(id: String?): MeasureCategory? =
        activeCategories().firstOrNull { it.id == id } ?: activeCategories().firstOrNull()

    /** 全模板字段总数，列表页展示用 */
    fun totalFieldCount(): Int = activeCategories().sumOf { it.activeFields().size }
}

/**
 * 单次拍摄的瞬时数据快照。拍下快门的那一刻就把系统层信息冻结，
 * 这样即使后面填数值填了很久，时间/GPS 仍是按快门时刻。
 */
data class CaptureMeta(
    val titleLine: String = "",
    val stickyLines: List<String> = emptyList(),
    /** 分类名，水印上单独一行，让人一眼知道这组数属于哪种结构 */
    val categoryName: String = "",
    val measureLines: List<String> = emptyList(),
    val timeMillis: Long = System.currentTimeMillis(),
    val lat: Double? = null,
    val lon: Double? = null,
    val address: String? = null,
    val showTime: Boolean = true,
    val showAddress: Boolean = true,
    val style: WatermarkStyle = WatermarkStyle(),
    val stickyFields: List<RecordField>? = null,
    val measureFields: List<RecordField>? = null,
    val weather: String? = null
)

/**
 * 一条拍摄记录，用于「记录」页与 CSV 导出。
 * 与照片文件按 [photoName] 关联。
 */
data class RecordField(
    val label: String = "",
    val unit: String = "",
    var values: List<String>? = emptyList(),
    /**
     * 记录自带角色信息，别去回溯模板——模板可能已经被改过或删了，
     * 而这条记录还得知道自己哪一行是桩号。存枚举名，老记录里没有就是 null。
     */
    var role: String? = null,
    /** 保留模板输入类型；旧记录未保存此项时允许文本编辑，避免无法输入中文。 */
    val type: String? = null,
    var appearance: FieldAppearance? = null,
    val warningRange: ReadingRange? = null
) {
    fun valueList(): List<String> = values ?: emptyList()

    fun isChainage(): Boolean = role == FieldRole.CHAINAGE.name

    fun inputFieldType(): FieldType = if (isChainage()) FieldType.TEXT
        else FieldType.entries.firstOrNull { it.name == type } ?: FieldType.TEXT

    /** 记录里的展示文本，与 FieldDef.format 保持同样的格式 */
    fun display(): String {
        val parts = valueList().map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return if (unit.isBlank()) "$label: ${parts.joinToString(" / ")}"
        else "$label: ${parts.joinToString(" / ")} $unit"
    }
}

data class CaptureRecord(
    val id: String = UUID.randomUUID().toString(),
    val timeMillis: Long = System.currentTimeMillis(),
    val templateId: String = "",
    val templateName: String = "",
    val categoryName: String = "",
    val photoName: String = "",
    /**
     * 记录来源：[SOURCE_CAMERA] 本机拍摄 / [SOURCE_IMPORT] 从相册导入。
     *
     * 导入的不写桩号递增基准——那张可能是几天前在别处拍的，拿它当选后的
     * 起点会把整条链带偏。老记录没有这个键，Gson 会填默认值，即"拍摄"。
     */
    val source: String = SOURCE_CAMERA,
    /**
     * 无水印原图的文件名（存在应用私有目录 originals/ 下）。
     *
     * 水印是烧进像素的，改数值必须从原图重画；没有原图就只能重拍。
     * v1.3 起每次拍摄都会留一份，v1.2 及更早的记录这里是 null。
     */
    val originalName: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val address: String? = null,
    val chainage: String = "",
    val chainageMeters: Double? = null,
    val moveMeters: Double? = null,
    /** 这张照片的水印落点，null 时按模板默认 */
    var watermarkAnchor: String? = null,
    var watermarkScale: Float? = null,
    /** 水印标题行，导出 CSV 与重绘都要用 */
    var titleLine: String = "",
    var showTime: Boolean? = null,
    var showAddress: Boolean? = null,
    var sticky: List<RecordField>? = emptyList(),
    var fields: List<RecordField>? = emptyList(),
    val watermarkStyle: WatermarkStyle? = null,
    val weather: String? = null,
    val watermarkPosition: WatermarkPosition? = null
) {
    fun stickyList(): List<RecordField> = sticky ?: emptyList()

    fun fieldList(): List<RecordField> = fields ?: emptyList()

    fun anchor(): WatermarkAnchor = WatermarkAnchor.fromKey(watermarkAnchor)

    /** 有原图才能重画水印；老记录改不了数值，只能删 */
    fun editable(): Boolean = !originalName.isNullOrBlank()

    fun imported(): Boolean = source == SOURCE_IMPORT

    fun withFields(list: List<RecordField>): CaptureRecord = copy(fields = list)
}


/** null 表示沿用模板的默认颜色/粗细；独立值对象可安全保存到历史记录。 */
data class TextFormat(val bold: Boolean? = null, val underline: Boolean = false,
    val color: String? = null, val background: String? = null)
data class FieldAppearance(val label: TextFormat? = null, val value: TextFormat? = null)
data class WatermarkStyle(val background: String = "#000000", val text: String = "#FFFFFF",
    val theme: String = "#8FE3B4", val opacity: Float = 0.6f) {
    fun alpha(): Int = ((if (opacity.isFinite()) opacity else 0.6f).coerceIn(0f, 1f) * 255).toInt()
}
