package com.measurecam.widget

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.measurecam.data.ReadingRange

object ReadingDialogs {
    fun range(context: Context, title: String, initial: ReadingRange?, apply: (ReadingRange?) -> Unit) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        fun input(label: String, value: Double?): EditText {
            box.addView(TextView(context).apply { text = label })
            return EditText(context).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                hint = "不限"
                setText(value?.toString().orEmpty())
                box.addView(this)
            }
        }
        val low = input("下限", initial?.min)
        val high = input("上限", initial?.max)
        val dialog = MaterialAlertDialogBuilder(context).setTitle("$title · 预警范围")
            .setMessage("范围包含端点。留空表示该端不限。超范围或相较前值变化达 10 倍时会提醒核实。")
            .setView(box).setPositiveButton("应用", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val values = listOf(low, high).map { field ->
                    val raw = field.text.toString().trim()
                    if (raw.isEmpty()) null else raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: run {
                        field.error = "请输入有效数值"
                        return@setOnClickListener
                    }
                }
                val min = values[0]
                val max = values[1]
                if (min != null && max != null && min > max) { high.error = "上限不能小于下限"; return@setOnClickListener }
                apply(if (min == null && max == null) null else ReadingRange(min, max))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun confirm(context: Context, warnings: List<String>, save: () -> Unit) {
        if (warnings.isEmpty()) { save(); return }
        MaterialAlertDialogBuilder(context).setTitle("请核实以下读数")
            .setMessage(warnings.joinToString("\n"))
            .setPositiveButton("核实并保存") { _, _ -> save() }
            .setNegativeButton("返回修改", null).show()
    }
}
