package com.multitask.bubble.ui

import android.view.LayoutInflater
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.multitask.bubble.R
import com.multitask.bubble.databinding.SheetAppPickerBinding
import kotlin.concurrent.thread

/** Bottom sheet listing launchable apps with a search field. Used from the main screen. */
class AppPickerSheet(
    private val activity: AppCompatActivity,
    private val onPick: (packageName: String) -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = SheetAppPickerBinding.inflate(LayoutInflater.from(activity))
        val adapter = AppListAdapter { entry ->
            dialog.dismiss()
            onPick(entry.packageName)
        }
        val linearLm = LinearLayoutManager(activity)
        val gridLm = GridLayoutManager(activity, 4)
        binding.list.layoutManager = linearLm
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        binding.root.findViewById<ImageButton>(R.id.btn_view_mode)?.setOnClickListener { btn ->
            val nextGrid = !adapter.isGrid
            adapter.setGridMode(nextGrid)
            binding.list.layoutManager = if (nextGrid) gridLm else linearLm
            (btn as ImageButton).setImageResource(if (nextGrid) R.drawable.ic_view_list else R.drawable.ic_apps)
            btn.contentDescription = activity.getString(if (nextGrid) R.string.view_mode_list else R.string.view_mode_grid)
        }

        dialog.setContentView(binding.root)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()

        thread {
            val apps = AppList.load(activity, emptyList())
            activity.runOnUiThread { adapter.submit(apps) }
        }
    }
}
