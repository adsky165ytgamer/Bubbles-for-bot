package com.multitask.bubble.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.multitask.bubble.databinding.ItemAppBinding
import com.multitask.bubble.databinding.SheetAppPickerBinding
import kotlin.concurrent.thread

/** Bottom sheet listing launchable apps with a search field. */
class AppPickerSheet(
    private val activity: AppCompatActivity,
    private val onPick: (packageName: String) -> Unit
) {
    private class Entry(val packageName: String, val label: String, val icon: Drawable)

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = SheetAppPickerBinding.inflate(LayoutInflater.from(activity))
        val adapter = Adapter { entry ->
            dialog.dismiss()
            onPick(entry.packageName)
        }
        binding.list.layoutManager = LinearLayoutManager(activity)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        dialog.setContentView(binding.root)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()

        thread {
            val apps = loadApps()
            activity.runOnUiThread { adapter.submit(apps) }
        }
    }

    private fun loadApps(): List<Entry> {
        val pm = activity.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { Entry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    private class Adapter(
        private val onClick: (Entry) -> Unit
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        private var all: List<Entry> = emptyList()
        private var shown: List<Entry> = emptyList()
        private var query = ""

        class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        fun submit(items: List<Entry>) {
            all = items
            filter(query)
        }

        fun filter(text: String) {
            query = text
            shown = if (text.isBlank()) all else all.filter { it.label.contains(text, ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = shown[position]
            holder.binding.appIcon.setImageDrawable(entry.icon)
            holder.binding.appLabel.text = entry.label
            holder.binding.appPackage.text = entry.packageName
            holder.binding.root.setOnClickListener { onClick(entry) }
        }
    }
}

