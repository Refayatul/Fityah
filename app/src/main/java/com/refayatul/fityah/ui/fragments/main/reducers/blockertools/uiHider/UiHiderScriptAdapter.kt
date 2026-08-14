package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.uiHider

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.refayatul.fityah.data.models.UiHiderScript
import com.refayatul.fityah.databinding.ItemUiHiderScriptBinding

class UiHiderScriptAdapter(
    private val onClick: (UiHiderScript) -> Unit,
    private val onToggle: (String, Boolean) -> Unit
) : ListAdapter<UiHiderScript, UiHiderScriptAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(val binding: ItemUiHiderScriptBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUiHiderScriptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val script = getItem(position)
        holder.binding.run {
            tvScriptTitle.text = script.label.ifBlank {
                script.packageName.substringAfterLast('.')
            }
            tvScriptSubtitle.text = script.packageName

            switchScriptEnabled.setOnCheckedChangeListener(null)
            switchScriptEnabled.isChecked = script.isEnabled
            switchScriptEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(script.id, checked)
            }

            root.setOnClickListener { onClick(script) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<UiHiderScript>() {
        override fun areItemsTheSame(oldItem: UiHiderScript, newItem: UiHiderScript) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UiHiderScript, newItem: UiHiderScript) =
            oldItem == newItem
    }
}
