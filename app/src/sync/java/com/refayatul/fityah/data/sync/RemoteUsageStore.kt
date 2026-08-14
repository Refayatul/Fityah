package com.refayatul.fityah.data.sync

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

class RemoteUsageStore(context: Context) {
    private val file = File(context.filesDir, "sync_remote_usage_v2.json")
    private var dirty = false
    private val records: HashMap<String, String> = synchronized(FILE_LOCK) { load(context) }

    init {
        if (dirty) flush()
    }

    private fun load(context: Context): HashMap<String, String> {
        runCatching {
            val legacy = File(context.filesDir, "sync_remote_usage.json")
            if (legacy.exists()) legacy.delete()
        }
        val loaded = try {
            val text = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(text)
            HashMap<String, String>().apply { obj.keys().forEach { put(it, obj.getString(it)) } }
        } catch (_: Exception) {
            HashMap()
        }
        pruneOld(loaded)
        return loaded
    }

    private fun pruneOld(map: HashMap<String, String>) {
        val today = LocalDate.now()
        val cutoff = today.minusDays(28)
        val futureCutoff = today.plusDays(28)
        val stale = map.entries.filter { (_, json) ->
            runCatching {
                val date = LocalDate.parse(JSONObject(json).getString("date"))
                date.isBefore(cutoff) || date.isAfter(futureCutoff)
            }.getOrDefault(true)
        }.map { it.key }
        if (stale.isNotEmpty()) {
            stale.forEach { map.remove(it) }
            dirty = true
        }
    }

    fun put(namespace: String, recordKey: String, payloadJson: String) {
        LocalDate.parse(JSONObject(payloadJson).getString("date"))
        records["$namespace/$recordKey"] = payloadJson
        dirty = true
    }

    fun remove(namespace: String, recordKey: String) {
        if (records.remove("$namespace/$recordKey") != null) dirty = true
    }

    fun flush() {
        if (!dirty) return
        val obj = JSONObject()
        records.forEach { (k, v) -> obj.put(k, v) }
        synchronized(FILE_LOCK) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(obj.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
                dirty = false
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
        }
    }

    fun clear() {
        records.clear()
        dirty = true
        // Replacing the file with an empty valid document is safer than a best-effort delete:
        // a failed delete must not expose the previous account's cache after account switching.
        flush()
    }

    fun appTotals(date: String, deviceIds: Set<String> = emptySet()): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((key, json) in records) {
            if (!key.startsWith("usage_app/")) continue
            if (deviceIds.isNotEmpty() && deviceIds.none { key.startsWith("usage_app/$it:") }) continue
            val o = runCatching { JSONObject(json) }.getOrNull() ?: continue
            if (o.optString("date") != date) continue
            val apps = o.optJSONObject("apps") ?: continue
            val keys = apps.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                if (pkg.isBlank() || pkg.length > 255) continue
                val ms = (apps.optJSONObject(pkg)?.optLong("ms") ?: 0L).coerceAtLeast(0L)
                out[pkg] = saturatedAdd(out[pkg] ?: 0L, ms)
            }
        }
        return out
    }

    fun websiteTotals(date: String, deviceIds: Set<String> = emptySet()): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((key, json) in records) {
            if (!key.startsWith("usage_web/")) continue
            if (deviceIds.isNotEmpty() && deviceIds.none { key.startsWith("usage_web/$it:") }) continue
            val o = runCatching { JSONObject(json) }.getOrNull() ?: continue
            if (o.optString("date") != date) continue
            val domains = o.optJSONObject("domains") ?: continue
            val keys = domains.keys()
            while (keys.hasNext()) {
                val domain = keys.next()
                if (domain.isBlank() || domain.length > 1000) continue
                val ms = (domains.optJSONObject(domain)?.optLong("ms") ?: 0L).coerceAtLeast(0L)
                out[domain] = saturatedAdd(out[domain] ?: 0L, ms)
            }
        }
        return out
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private companion object {
        val FILE_LOCK = Any()
    }
}
