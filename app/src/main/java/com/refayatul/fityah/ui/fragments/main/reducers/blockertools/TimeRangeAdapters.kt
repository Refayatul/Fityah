package com.refayatul.fityah.ui.fragments.main.reducers.blockertools

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.refayatul.fityah.data.models.TimeInterval
import com.refayatul.fityah.databinding.ItemDayTimeRangesBinding
import com.refayatul.fityah.databinding.ItemTimeRangeIntervalBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DayItem(
    val name: String,
    val dayIndex: Int,
    var isActive: Boolean,
    val intervals: MutableList<TimeInterval>
)

class TimeIntervalAdapter(
    private val intervals: List<TimeInterval>,
    private val onTimeClick: (TimeInterval, Boolean, Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<TimeIntervalAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTimeRangeIntervalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTimeRangeIntervalBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val interval = intervals[position]
        
        updateTimeText(holder.binding.llStartTime, interval.startHour, interval.startMinute)
        updateTimeText(holder.binding.llEndTime, interval.endHour, interval.endMinute)

        holder.binding.llStartTime.setOnClickListener {
            onTimeClick(interval, true, holder.adapterPosition)
        }

        holder.binding.llEndTime.setOnClickListener {
            onTimeClick(interval, false, holder.adapterPosition)
        }

        holder.binding.btnRemove.setOnClickListener {
            onRemove(holder.adapterPosition)
        }
    }

    override fun getItemCount() = intervals.size

    private fun updateTimeText(textView: TextView, hour: Int, minute: Int) {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        val isSystem24Hour = DateFormat.is24HourFormat(textView.context)
        val formatPattern = if (isSystem24Hour) "HH:mm" else "hh:mm a"
        val sdf = SimpleDateFormat(formatPattern, Locale.getDefault())
        textView.text = sdf.format(calendar.time)
    }
}

class DayAdapter(
    private val days: List<DayItem>,
    private val onAddTimeInterval: (DayItem, Int) -> Unit,
    private val onTimeClick: (TimeInterval, Boolean, Int, Int) -> Unit,
    private val onRemoveInterval: (Int, Int) -> Unit,
    private val onDisabledClick: () -> Unit = {},
    private val onDayToggled: (DayItem, Int, Boolean) -> Boolean = { _, _, _ -> true }
) : RecyclerView.Adapter<DayAdapter.ViewHolder>() {

    var isInteractionEnabled: Boolean = true
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(val binding: ItemDayTimeRangesBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDayTimeRangesBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val day = days[position]
        holder.binding.dayLabel.text = day.name
        
        holder.binding.switchDayActive.setOnCheckedChangeListener(null)
        holder.binding.switchDayActive.isChecked = day.isActive
        
        if (!isInteractionEnabled) {
            holder.binding.switchDayActive.isEnabled = false
            holder.binding.root.setOnClickListener {
                onDisabledClick()
            }
        } else {
            holder.binding.switchDayActive.isEnabled = true
            holder.binding.root.setOnClickListener(null)
        }

        holder.binding.intervalsContainer.visibility = if (day.isActive) View.VISIBLE else View.GONE
        holder.binding.btnAddInterval.visibility = if (day.isActive && isInteractionEnabled) View.VISIBLE else View.GONE

        holder.binding.switchDayActive.setOnCheckedChangeListener { _, isChecked ->
            if (onDayToggled(day, holder.adapterPosition, isChecked)) {
                day.isActive = isChecked
                holder.binding.intervalsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                holder.binding.btnAddInterval.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked && day.intervals.isEmpty()) {
                    onAddTimeInterval(day, holder.adapterPosition)
                }
            } else {
                holder.binding.switchDayActive.setOnCheckedChangeListener(null)
                holder.binding.switchDayActive.isChecked = !isChecked
                notifyItemChanged(holder.adapterPosition)
            }
        }

        val adapter = TimeIntervalAdapter(
            day.intervals,
            onTimeClick = { interval, isStart, intervalPos ->
                onTimeClick(interval, isStart, holder.adapterPosition, intervalPos)
            },
            onRemove = { intervalPos ->
                onRemoveInterval(holder.adapterPosition, intervalPos)
            }
        )
        holder.binding.intervalsContainer.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.binding.intervalsContainer.adapter = adapter

        holder.binding.btnAddInterval.setOnClickListener {
            onAddTimeInterval(day, holder.adapterPosition)
        }
    }

    override fun getItemCount() = days.size
}
