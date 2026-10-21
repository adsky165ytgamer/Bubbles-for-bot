package com.multitask.bubble.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.multitask.bubble.R
import com.multitask.bubble.databinding.ItemAppBinding

class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val recent: Boolean
)

object AppList {
    /** Every launchable app. Recent bubbles come first, the rest are alphabetical. Blocking. */
    fun load(context: Context, recents: List<String>): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                val pkg = it.activityInfo.packageName
                AppEntry(pkg, it.loadLabel(pm).toString(), it.loadIcon(pm), pkg in recents)
            }
        val recentFirst = recents.mapNotNull { pkg -> all.firstOrNull { it.packageName == pkg } }
        val rest = all.filter { !it.recent }.sortedBy { it.label.lowercase() }
        return recentFirst + rest
    }
}

class AppListAdapter(
    private val onClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query = ""

    fun submit(items: List<AppEntry>) {
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
        val context = holder.binding.root.context
        holder.binding.appIcon.setImageDrawable(entry.icon)
        holder.binding.appLabel.text = entry.label
        holder.binding.appPackage.text =
            if (entry.recent) context.getString(R.string.recent_label) else entry.packageName
        holder.binding.root.setOnClickListener { onClick(entry) }
    }
}
