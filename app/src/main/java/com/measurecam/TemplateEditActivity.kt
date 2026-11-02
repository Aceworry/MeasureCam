package com.measurecam

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.measurecam.widget.DragOrder
import com.measurecam.widget.StyleDialogs
import com.measurecam.widget.ReadingDialogs
import com.measurecam.data.ReadingRange
import com.measurecam.data.FieldAppearance
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.measurecam.data.CaptureTemplate
import com.measurecam.data.FieldDef
import com.measurecam.data.FieldRole
import com.measurecam.data.FieldType
import com.measurecam.data.MeasureCategory
import com.measurecam.data.StickyField
import com.measurecam.data.TemplateRepository
import com.measurecam.databinding.ActivityTemplateEditBinding
import com.measurecam.databinding.ItemCategoryEditBinding
import com.measurecam.databinding.ItemMeasureEditBinding
import com.measurecam.databinding.ItemStickyEditBinding
import java.util.UUID

class TemplateEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "template_id"
    }

    private lateinit var binding: ActivityTemplateEditBinding
    private var template: CaptureTemplate? = null

    private val typeNames by lazy {
        arrayOf(
            getString(R.string.type_decimal),
            getString(R.string.type_integer),
            getString(R.string.type_text)
        )
    }

    private val countNames by lazy {
        Array(6) { "${it + 1} 值" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_ID)
        val found = TemplateRepository.load(this).firstOrNull { it.id == id }
        if (found == null) {
            finish()
            return
        }
        template = Gson().fromJson(Gson().toJson(found), CaptureTemplate::class.java)

        bindExisting(template!!)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.btnStyle.setOnClickListener {
            val t = template!!
            StyleDialogs.watermark(this, t.watermarkStyle, t.scale(), t.showWeather) { style, scale, weather ->
                t.watermarkStyle = style; t.watermarkScale = scale; t.showWeather = weather
            }
        }

        binding.btnAddSticky.setOnClickListener { addStickyRow(StickyField(label = "")) }
        binding.btnAddCategory.setOnClickListener {
            addCategoryBlock(MeasureCategory(name = "", fields = mutableListOf(FieldDef(label = ""))))
        }
        binding.btnSave.setOnClickListener { saveAndFinish() }
    }

    private fun bindExisting(t: CaptureTemplate) {
        binding.etName.setText(t.name)
        binding.etTitle.setText(t.title)
        binding.cbTime.isChecked = t.showTime
        binding.cbAddress.isChecked = t.showAddress
        binding.cbAutoChainage.isChecked = t.autoChainage
        binding.etAdvanceLimit.setText(t.advanceLimit().toString())
        binding.etStep.setText(
            if (t.chainageStep % 1.0 == 0.0) t.chainageStep.toInt().toString()
            else t.chainageStep.toString()
        )

        binding.containerSticky.removeAllViews()
        t.stickyList().forEach { addStickyRow(it) }

        binding.containerCategory.removeAllViews()
        t.categoryList().forEach { addCategoryBlock(it) }
    }

    private fun addStickyRow(field: StickyField) {
        val row = ItemStickyEditBinding.inflate(layoutInflater, binding.containerSticky, false)
        row.etLabel.setText(field.label)
        // 把原 id 挂在 tag 上，保存时沿用，避免丢掉用户在相机页填过的常驻值
        row.root.tag = field.id
        row.btnRemove.setOnClickListener {
            binding.containerSticky.removeView(row.root)
        }
        decorate(row.root, row.root, binding.containerSticky, field.appearance)
        binding.containerSticky.addView(row.root)
    }

    private fun addCategoryBlock(cat: MeasureCategory) {
        val block = ItemCategoryEditBinding.inflate(layoutInflater, binding.containerCategory, false)
        block.etCatName.setText(cat.name)
        block.root.tag = cat.id

        block.btnRemoveCategory.setOnClickListener {
            binding.containerCategory.removeView(block.root)
        }
        block.btnAddField.setOnClickListener {
            addFieldRow(block.containerFields, FieldDef(label = ""))
        }
        cat.fieldList().forEach { addFieldRow(block.containerFields, it) }

        (block.root.getChildAt(0) as LinearLayout).addView(DragOrder.handle(block.root, binding.containerCategory), 0)
        binding.containerCategory.addView(block.root)
    }

    private fun addFieldRow(container: LinearLayout, field: FieldDef) {
        val row = ItemMeasureEditBinding.inflate(layoutInflater, container, false)
        row.etLabel.setText(field.label)
        row.etUnit.setText(field.unit)
        row.root.tag = field.id

        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeNames)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        row.spType.adapter = typeAdapter
        row.spType.setSelection(
            when (field.type) {
                FieldType.DECIMAL -> 0
                FieldType.INTEGER -> 1
                FieldType.TEXT -> 2
            }
        )

        val countAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, countNames)
        countAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        row.spCount.adapter = countAdapter
        row.spCount.setSelection((field.slots() - 1).coerceIn(0, 5))

        row.cbChainage.isChecked = field.isChainage()
        row.btnRemove.setOnClickListener { container.removeView(row.root) }
        decorate(row.root, row.root.getChildAt(0) as LinearLayout, container, field.appearance)
        row.root.setTag(R.id.field_range, field.warningRange)
        val rangeButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun rangeLabel(range: ReadingRange?) = if (range == null) "预警范围 · 未设置"
            else "预警范围 · " + (range.min?.toString() ?: "不限") + " ～ " + (range.max?.toString() ?: "不限")
        rangeButton.text = rangeLabel(field.warningRange)
        rangeButton.setOnClickListener {
            ReadingDialogs.range(this, row.etLabel.text.toString(), row.root.getTag(R.id.field_range) as? ReadingRange) {
                row.root.setTag(R.id.field_range, it)
                rangeButton.text = rangeLabel(it)
            }
        }
        fun updateRangeButton() {
            rangeButton.visibility = if (row.spType.selectedItemPosition != 2 && !row.cbChainage.isChecked) View.VISIBLE else View.GONE
        }
        row.cbChainage.setOnCheckedChangeListener { _, _ -> updateRangeButton() }
        row.spType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateRangeButton()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = updateRangeButton()
        }
        updateRangeButton()
        row.root.addView(rangeButton)
        container.addView(row.root)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun decorate(row: LinearLayout, bar: LinearLayout, parent: LinearLayout, appearance: FieldAppearance?) {
        row.setTag(R.id.field_appearance, appearance)
        bar.addView(DragOrder.handle(row, parent), 0)
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "格式"; textSize = 12f; minWidth = 0; minimumWidth = 0
            cornerRadius = (12 * resources.displayMetrics.density).toInt()
            setPadding(6, 0, 6, 0)
            setOnClickListener {
                val label = row.findViewById<EditText>(R.id.et_label).text.toString().ifBlank { "字段" }
                StyleDialogs.field(this@TemplateEditActivity, label, row.getTag(R.id.field_appearance) as? FieldAppearance) {
                    row.setTag(R.id.field_appearance, it)
                }
            }
        }, LinearLayout.LayoutParams((52 * resources.displayMetrics.density).toInt(), -2))
    }

    private fun saveAndFinish() {
        val t = template ?: return
        val list = TemplateRepository.load(this)
        val limit = binding.etAdvanceLimit.text.toString().trim().toIntOrNull()
        if (limit == null || limit <= 0) {
            binding.etAdvanceLimit.error = "请输入大于 0 的整数米数"
            binding.etAdvanceLimit.requestFocus()
            return
        }
        t.maxChainageAdvance = limit

        // 先留存旧数据，用于继承 id 与默认值
        val oldSticky = t.stickyList().toList()
        val oldFields = t.categoryList().flatMap { it.fieldList() }

        t.name = binding.etName.text.toString().trim().ifEmpty { "未命名" }
        t.title = binding.etTitle.text.toString().trim()
        t.showTime = binding.cbTime.isChecked
        t.showAddress = binding.cbAddress.isChecked
        t.autoChainage = binding.cbAutoChainage.isChecked
        t.chainageStep = binding.etStep.text.toString().trim().toDoubleOrNull() ?: 20.0

        val newSticky = mutableListOf<StickyField>()
        for (i in 0 until binding.containerSticky.childCount) {
            val row = binding.containerSticky.getChildAt(i)
            val label = row.findViewById<EditText>(R.id.et_label).text.toString().trim()
            if (label.isEmpty()) continue
            val id = row.tag as? String ?: UUID.randomUUID().toString()
            val previous = oldSticky.firstOrNull { it.id == id }
            newSticky.add(StickyField(id = id, label = label, value = previous?.value.orEmpty(), enabled = previous?.enabled ?: true, appearance = row.getTag(R.id.field_appearance) as? FieldAppearance))
        }
        t.sticky = newSticky

        val newCats = mutableListOf<MeasureCategory>()
        for (i in 0 until binding.containerCategory.childCount) {
            val block = binding.containerCategory.getChildAt(i)
            val name = block.findViewById<EditText>(R.id.et_cat_name).text.toString().trim()
            if (name.isEmpty()) continue
            val catId = block.tag as? String ?: UUID.randomUUID().toString()
            val fieldBox = block.findViewById<LinearLayout>(R.id.container_fields)

            val fields = mutableListOf<FieldDef>()
            for (j in 0 until fieldBox.childCount) {
                val row = fieldBox.getChildAt(j)
                val label = row.findViewById<EditText>(R.id.et_label).text.toString().trim()
                if (label.isEmpty()) continue
                val unit = row.findViewById<EditText>(R.id.et_unit).text.toString().trim()
                val typeSpin = row.findViewById<Spinner>(R.id.sp_type)
                val countSpin = row.findViewById<Spinner>(R.id.sp_count)
                val chainCb = row.findViewById<CheckBox>(R.id.cb_chainage)

                val type = when (typeSpin.selectedItemPosition) {
                    0 -> FieldType.DECIMAL
                    1 -> FieldType.INTEGER
                    else -> FieldType.TEXT
                }
                val count = (countSpin.selectedItemPosition + 1).coerceIn(1, 6)
                val role = if (chainCb.isChecked) FieldRole.CHAINAGE else FieldRole.NONE
                val id = row.tag as? String ?: UUID.randomUUID().toString()
                val previous = oldFields.firstOrNull { it.id == id }

                fields.add(
                    FieldDef(
                        id = id,
                        label = label,
                        unit = unit,
                        type = type,
                        defaultValue = previous?.defaultValue.orEmpty(),
                        valueCount = count,
                        role = role,
                        enabled = previous?.enabled ?: true,
                        warningRange = if (type != FieldType.TEXT && role != FieldRole.CHAINAGE) row.getTag(R.id.field_range) as? ReadingRange else null,
                        appearance = row.getTag(R.id.field_appearance) as? FieldAppearance
                    )
                )
            }

            // 一个分类里只允许有一个桩号字段，否则递增逻辑不知道跟谁走
            var seenChainage = false
            fields.replaceAll { f ->
                if (f.isChainage()) {
                    if (seenChainage) f.copy(role = FieldRole.NONE) else {
                        seenChainage = true
                        f
                    }
                } else {
                    f
                }
            }

            newCats.add(MeasureCategory(id = catId, name = name, fields = fields, enabled = t.categoryList().firstOrNull { it.id == catId }?.enabled ?: true))
        }
        t.categories = newCats

        val index = list.indexOfFirst { it.id == t.id }
        if (index < 0) return
        val updated = list.toMutableList().apply { set(index, t) }
        if (!TemplateRepository.save(this, updated)) {
            Toast.makeText(this, R.string.record_write_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.template_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
