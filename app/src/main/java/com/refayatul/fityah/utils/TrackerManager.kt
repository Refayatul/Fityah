package com.refayatul.fityah.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.regex.Pattern

class TrackerManager(private val context: Context) {

    private val trackers = mutableListOf<TrackerEntry>()

    data class TrackerEntry(val name: String, val pattern: Pattern)

    init {
        loadTrackers()
    }

    private fun loadTrackers() {
        try {
            val jsonString = context.assets.open("trackers.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val trackersObj = root.getJSONObject("trackers")
            
            val keys = trackersObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val tracker = trackersObj.getJSONObject(key)
                val name = tracker.getString("name")
                val networkSignature = tracker.getString("network_signature")
                
                if (networkSignature.isNotEmpty()) {
                    try {
                        val pattern = Pattern.compile(networkSignature, Pattern.CASE_INSENSITIVE)
                        trackers.add(TrackerEntry(name, pattern))
                        if (name.contains("Google Analytics")) {
                            Log.d("TrackerManager", "Loaded Google Analytics pattern: $networkSignature")
                        }
                    } catch (e: Exception) {
                        Log.e("TrackerManager", "Failed to compile pattern for $name: $networkSignature", e)
                    }
                }
            }
            Log.i("TrackerManager", "Loaded ${trackers.size} tracker patterns")
        } catch (e: Exception) {
            Log.e("TrackerManager", "Failed to load trackers.json", e)
        }
    }

    fun getTrackerName(domain: String): String? {
        for (tracker in trackers) {
            if (tracker.pattern.matcher(domain).find()) {
                return tracker.name
            }
        }
        return null
    }
}
