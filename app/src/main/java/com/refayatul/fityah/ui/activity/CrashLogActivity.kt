package com.refayatul.fityah.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.refayatul.fityah.R
import com.refayatul.fityah.databinding.ActivityCrashLogBinding
import java.io.File

class CrashLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val logFile = File(filesDir, "crash_log.txt")
        val content = if (logFile.exists()) {
            try {
                val text = logFile.readText()
                if (text.length > 50000) {
                    "...${text.takeLast(50000)}"
                } else {
                    text
                }
            } catch (e: Exception) {
                "Error reading crash logs."
            }
        } else {
            "No crash logs available."
        }

        binding.tvCrashLogs.text = content

        binding.btnShare.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Fityah Crash Logs")
                putExtra(Intent.EXTRA_TEXT, content)
            }
            startActivity(Intent.createChooser(intent, "Share Crash Logs"))
        }

        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnRestart.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }

        binding.btnClear.setOnClickListener {
            if (logFile.exists() && logFile.delete()) {
                binding.tvCrashLogs.text = getString(R.string.crash_logs_none_available)
            }
        }
    }
}
