package com.multitask.bubble.ui

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        binding.list.layoutManager = LinearLayoutManager(activity)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

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
