package com.refayatul.fityah.utils

import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelper {
    companion object {
        fun findElementById(node: AccessibilityNodeInfo?, id: String?): AccessibilityNodeInfo? {
            if (node == null) return null
            var targetNode: AccessibilityNodeInfo? = null
            if(node.viewIdResourceName == id) return node
            try {
                targetNode = node.findAccessibilityNodeInfosByViewId(id!!)[0]
            } catch (e: Exception) {
            }
            return targetNode
        }

    }
}
