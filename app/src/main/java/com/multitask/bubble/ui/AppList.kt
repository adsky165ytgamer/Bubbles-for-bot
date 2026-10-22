package com.multitask.bubble.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.multitask.bubble.R
import com.multitask.bubble.databinding.ItemAppBinding
import com.multitask.bubble.databinding.ItemAppGridBinding

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
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
    }

    class ListHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)
    class GridHolder(val binding: ItemAppGridBinding) : RecyclerView.ViewHolder(binding.root)

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query = ""
    var isGrid: Boolean = false
        private set

    fun setGridMode(grid: Boolean) {
        if (isGrid != grid) {
            isGrid = grid
            notifyDataSetChanged()
        }
    }

    fun submit(items: List<AppEntry>) {
        all = items
        filter(query)
    }

    fun filter(text: String) {
        query = text
        shown = if (text.isBlank()) all else all.filter { it.label.contains(text, ignoreCase = true) }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (isGrid) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            GridHolder(ItemAppGridBinding.inflate(inflater, parent, false))
        } else {
            ListHolder(ItemAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = shown.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = shown[position]
        val context = holder.itemView.context
        when (holder) {
            is ListHolder -> {
                holder.binding.appIcon.setImageDrawable(entry.icon)
                holder.binding.appLabel.text = entry.label
                holder.binding.appPackage.text =
                    if (entry.recent) context.getString(R.string.recent_label) else entry.packageName
                holder.binding.root.setOnClickListener { onClick(entry) }
            }
            is GridHolder -> {
                holder.binding.appIcon.setImageDrawable(entry.icon)
                holder.binding.appLabel.text = entry.label
                holder.binding.root.setOnClickListener { onClick(entry) }
            }
        }
    }
}
