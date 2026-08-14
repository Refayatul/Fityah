package com.refayatul.fityah.ui.fragments.main.focus

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.refayatul.fityah.R

class RulerAdapter : RecyclerView.Adapter<RulerAdapter.TickViewHolder>() {

    class TickViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val vTick: View = view.findViewById(R.id.vTick)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TickViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ruler_tick, parent, false)
        return TickViewHolder(view)
    }

    override fun onBindViewHolder(holder: TickViewHolder, position: Int) {
        val params = holder.vTick.layoutParams as FrameLayout.LayoutParams
        val majorColor = MaterialColors.getColor(
            holder.vTick,
            com.google.android.material.R.attr.colorOutline
        )
        val minorColor = MaterialColors.getColor(
            holder.vTick,
            com.google.android.material.R.attr.colorOutlineVariant
        )

        if (position % 5 == 0) {
            params.height = 100
            holder.vTick.setBackgroundColor(majorColor)
        } else {
            params.height = 60
            holder.vTick.setBackgroundColor(minorColor)
        }
        holder.vTick.layoutParams = params
    }

    override fun getItemCount(): Int = Int.MAX_VALUE
}
