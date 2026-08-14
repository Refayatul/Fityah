package com.refayatul.fityah.blockers.uihider

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.refayatul.fityah.blockers.BaseBlocker
import com.refayatul.fityah.blockers.uihider.script.Budget
import com.refayatul.fityah.blockers.uihider.script.Interpreter
import com.refayatul.fityah.blockers.uihider.script.Parser
import com.refayatul.fityah.blockers.uihider.script.ScriptError
import com.refayatul.fityah.blockers.uihider.script.Stmt
import com.refayatul.fityah.data.models.UiHiderConfig
import com.refayatul.fityah.hardcoded.allScripts
import com.refayatul.fityah.services.BaseBlockingService

/**
 * Advanced, scriptable view hider. Each user script is bound to a package and runs — inside the
 * AppBlockerService background worker — only while that app is foreground. Scripts read the
 * accessibility tree, compute geometry, and draw overlays / press back / press home.
 *
 * Robustness: every run is sandboxed with a [Budget] and wrapped in try/catch so a faulty or
 * runaway script can never crash or hang the accessibility service.
 */
class UiHider : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_UI_HIDER = "com.refayatul.fityah.refresh.uihider"
        private const val MIN_RUN_INTERVAL_MS = 80L
    }

    private lateinit var service: BaseBlockingService
    private lateinit var overlay: UiHiderOverlayManager
    private var store: ScriptStore? = null

    private var config = UiHiderConfig()
    private var settingsJob: Job? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenMap: Map<String, Any?> = emptyMap()

    @Volatile private var scriptsByPackage: Map<String, List<CompiledScript>> = emptyMap()

    private var lastPackage = ""
    private var lastRunAt = 0L

    // Last overlay set handed to the manager; lets us skip re-posting an identical frame.
    @Volatile private var lastCommands: List<DrawCommand> = emptyList()

    private class CompiledScript(val id: String, val program: List<Stmt>)

    fun setupBlocker(service: BaseBlockingService) {
        this.service = service
        overlay = UiHiderOverlayManager(service)
        if (store == null) store = ScriptStore(java.io.File(service.filesDir, "uihider_store.json"))
        val metrics = service.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenMap = mapOf("width" to screenWidth.toDouble(), "height" to screenHeight.toDouble())

        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                config = settings.uiHiderConfig
                recompile()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter(INTENT_ACTION_REFRESH_UI_HIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun removeReceivers() {
        try { service.unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        settingsJob?.cancel()
        clearOverlays()
        store?.close()
        store = null
    }

    private fun recompile() {
        val newMap = HashMap<String, MutableList<CompiledScript>>()
        if (config.isActive) {
            for (script in config.allScripts()) {
                if (!script.isEnabled || script.packageName.isBlank() || script.source.isBlank()) continue
                try {
                    val program = Parser.parse(script.source)
                    newMap.getOrPut(script.packageName) { ArrayList() }
                        .add(CompiledScript(script.id.ifEmpty { script.packageName }, program))
                } catch (e: ScriptError) {
                    Log.w("UiHider", "Compile error in '${script.label}': ${e.message}")
                }
            }
        }
        scriptsByPackage = newMap
        if (!config.isActive) clearOverlays()
    }

    fun doUiHiderCheck(event: AccessibilityEvent?) {
        if (event == null || !config.isActive) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName) return

        val scripts = scriptsByPackage[pkg]
        if (scripts.isNullOrEmpty()) {
            if (lastPackage != pkg) {
                clearOverlays()
                lastPackage = pkg
            }
            return
        }
        lastPackage = pkg

        val now = SystemClock.uptimeMillis()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isWindowChange && now - lastRunAt < MIN_RUN_INTERVAL_MS) return
        lastRunAt = now

        runScripts(pkg, scripts, event)
    }

    private fun runScripts(pkg: String, scripts: List<CompiledScript>, event: AccessibilityEvent) {
        val root = service.rootInActiveWindow ?: return
        try {
            val commands = ArrayList<DrawCommand>()
            val globals = buildGlobals(pkg, event)
            for (compiled in scripts) {
                val budget = Budget()
                val runtime = UiHiderRuntime(service, root, budget, globals, compiled.id, store!!)
                try {
                    Interpreter(runtime, budget).run(compiled.program)
                    for (cmd in runtime.drawCommands) {
                        commands.add(cmd.copy(key = "${compiled.id}::${cmd.key}"))
                    }
                } catch (e: ScriptError) {
                    Log.w("UiHider", "Runtime error in script '${compiled.id}': ${e.message}")
                } finally {
                    if (runtime.output.isNotEmpty()) {
                        Log.i("UiHider", "[${compiled.id}] ${runtime.output.toString().trimEnd()}")
                    }
                    runtime.finish()
                }
            }
            if (commands != lastCommands) {
                overlay.apply(commands)
                lastCommands = commands
            }
        } catch (t: Throwable) {
            Log.e("UiHider", "Error running scripts for $pkg", t)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    /** Remove all overlays and invalidate the dedupe cache so the next run re-applies cleanly. */
    private fun clearOverlays() {
        overlay.clearAll()
        lastCommands = emptyList()
    }

    private fun buildGlobals(pkg: String, event: AccessibilityEvent): Map<String, Any?> = mapOf(
        "app" to pkg,
        "screen" to screenMap,
        "event" to mapOf(
            "type" to eventTypeName(event.eventType),
            "package" to pkg,
            "text" to event.text.joinToString(" ").takeIf { it.isNotEmpty() },
            "class" to event.className?.toString()
        )
    )

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scrolled"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "clicked"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "selected"
        else -> "other"
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == INTENT_ACTION_REFRESH_UI_HIDER) {
                clearOverlays()
            }
        }
    }
}
