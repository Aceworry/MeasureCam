package com.measurecam

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.measurecam.data.CaptureTemplate
import com.measurecam.data.FieldDef
import com.measurecam.data.FieldType
import com.measurecam.data.MeasureCategory
import com.measurecam.data.StickyField
import com.measurecam.data.TemplateRepository
import com.measurecam.databinding.ActivityTemplateListBinding
import com.measurecam.databinding.ItemTemplateBinding

class TemplateListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplateListBinding
    private val items = mutableListOf<CaptureTemplate>()
    private val adapter = TemplateAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { createTemplate() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun reload() {
        items.clear()
        items.addAll(TemplateRepository.load(this))
        binding.tvCount.text = getString(R.string.template_count, items.size)
        adapter.notifyDataSetChanged()
    }

    private fun createTemplate() {
        val list = TemplateRepository.load(this)
        val t = CaptureTemplate(
            name = "模板 ${list.size + 1}",
            sticky = mutableListOf(StickyField(label = "项目名称")),
            categories = mutableListOf(
                MeasureCategory(
                    name = "默认分类",
                    fields = mutableListOf(
                        FieldDef(label = "读数", unit = "", type = FieldType.DECIMAL)
                    )
                )
            )
        )
        if (!TemplateRepository.save(this, (list + t).toMutableList())) {
            toast(getString(R.string.record_write_failed)); return
        }
        openEditor(t.id)
    }

    private fun openEditor(templateId: String) {
        startActivity(
            Intent(this, TemplateEditActivity::class.java)
                .putExtra(TemplateEditActivity.EXTRA_ID, templateId)
        )
    }

    private fun confirmDelete(target: CaptureTemplate) {
        val list = TemplateRepository.load(this)
        if (list.size <= 1) {
            toast(getString(R.string.keep_one_template))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_msg, target.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                // 必须在删除前判断，删除后 selectedTemplate 已经自动回落
                val wasSelected = TemplateRepository.selectedTemplate(this).id == target.id
                val updated = list.filter { it.id != target.id }.toMutableList()
                if (!TemplateRepository.save(this, updated)) {
                    toast(getString(R.string.record_write_failed)); return@setPositiveButton
                }
                if (wasSelected) {
                    updated.firstOrNull()?.let { TemplateRepository.select(this, it.id) }
                }
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private inner class TemplateAdapter : RecyclerView.Adapter<TemplateAdapter.VH>() {

        inner class VH(val item: ItemTemplateBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val item = ItemTemplateBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(item)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            val selected = t.id == TemplateRepository.selectedTemplate(this@TemplateListActivity).id
            holder.item.tvCurrent.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            holder.item.root.strokeColor = ContextCompat.getColor(this@TemplateListActivity, if (selected) R.color.brand else R.color.divider)
            holder.item.tvName.text = t.name
            holder.item.tvSummary.text = getString(
                R.string.summary_fmt,
                t.activeSticky().size,
                t.activeCategories().size,
                t.totalFieldCount()
            )

            holder.item.root.setOnClickListener { openEditor(t.id) }
            holder.item.btnMore.setOnClickListener { view ->
                PopupMenu(this@TemplateListActivity, view).apply {
                    menu.add("编辑模板").setOnMenuItemClickListener { openEditor(t.id); true }
                    menu.add("删除模板").setOnMenuItemClickListener { confirmDelete(t); true }
                }.show()
            }
            holder.item.root.setOnLongClickListener {
                confirmDelete(t)
                true
            }
        }
    }
}
