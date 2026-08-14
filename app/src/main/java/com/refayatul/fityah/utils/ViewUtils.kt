package com.refayatul.fityah.utils

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import com.refayatul.fityah.R

object ViewUtils {
    fun showHelpPopup(anchor: View, text: String, url: String? = null) {
        val context = anchor.context
        val popupView = LayoutInflater.from(context).inflate(R.layout.layout_help_tooltip, null)
        val textView = popupView.findViewById<TextView>(R.id.tv_help_text)
        textView.text = text

        val learnMoreBtn = popupView.findViewById<Button>(R.id.btn_learn_more)
        if (url != null) {
            learnMoreBtn.visibility = View.VISIBLE
            learnMoreBtn.setOnClickListener {
                openUrl(context, url)
            }
        } else {
            learnMoreBtn.visibility = View.GONE
        }

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        
        popupWindow.elevation = 8f
        popupWindow.showAsDropDown(anchor, 0, 0)
    }

    private fun openUrl(context: android.content.Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
