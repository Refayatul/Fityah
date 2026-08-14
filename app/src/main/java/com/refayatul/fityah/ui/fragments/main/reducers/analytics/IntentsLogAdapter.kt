package com.refayatul.fityah.ui.fragments.main.reducers.analytics

import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.refayatul.fityah.R
import com.refayatul.fityah.data.db.IntentLogEntity
import com.refayatul.fityah.databinding.ItemIntentLogBinding

class IntentsLogAdapter(private val onDelete: (Int) -> Unit) : ListAdapter<IntentLogEntity, IntentsLogAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemIntentLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemIntentLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.run {
            intentText.text = item.intentText
            timeText.text = DateUtils.getRelativeTimeSpanString(item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            durationText.text = durationText.context.getString(R.string.intent_unlocked_for, item.unlockedDurationMs / 60_000)

            val pm = root.context.packageManager
            try {
                val appInfo = pm.getApplicationInfo(item.packageName, 0)
                appName.text = pm.getApplicationLabel(appInfo)
                appIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            } catch (e: PackageManager.NameNotFoundException) {
                appName.text = item.packageName
            }
            
            btnDelete.setOnClickListener {
                onDelete(item.id)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<IntentLogEntity>() {
        override fun areItemsTheSame(oldItem: IntentLogEntity, newItem: IntentLogEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: IntentLogEntity, newItem: IntentLogEntity): Boolean {
            return oldItem == newItem
        }
    }
}
