package net.pangolin.Pangolin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import net.pangolin.Pangolin.util.ConfigManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : BaseNavigationActivity() {

    private val tag = "LogsActivity"
    private lateinit var configManager: ConfigManager
    private lateinit var logStatusText: TextView
    private lateinit var downloadLogsButton: Button
    private lateinit var logFileInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        configManager = ConfigManager.getInstance(this)

        // Setup navigation
        setupNavigation(
            findViewById(R.id.drawer_layout),
            findViewById(R.id.nav_view),
            findViewById(R.id.toolbar)
        )

        // Initialize views
        logStatusText = findViewById(R.id.logStatusText)
        downloadLogsButton = findViewById(R.id.downloadLogsButton)
        logFileInfoText = findViewById(R.id.logFileInfoText)

        // Setup download button
        downloadLogsButton.setOnClickListener {
            downloadLogFile()
        }

        // Update UI based on configuration
        updateLogStatus()
    }

    override fun onResume() {
        super.onResume()
        updateLogStatus()
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_logs
    }

    private fun updateLogStatus() {
        val config = configManager.config.value
        val logCollectionEnabled = config.logCollectionEnabled ?: false
        val logFile = File(filesDir, "pangolin_go.log")

        if (logCollectionEnabled) {
            if (logFile.exists() && logFile.length() > 0) {
                logStatusText.text = "Log collection is enabled. Logs are being saved."
                downloadLogsButton.isEnabled = true
                
                // Show file info
                val fileSize = formatFileSize(logFile.length())
                val lastModified = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                    .format(Date(logFile.lastModified()))
                logFileInfoText.text = "Log file size: $fileSize\nLast modified: $lastModified"
                logFileInfoText.visibility = TextView.VISIBLE
            } else {
                logStatusText.text = "Log collection is enabled, but no logs have been generated yet. Connect to the tunnel to generate logs."
                downloadLogsButton.isEnabled = false
                logFileInfoText.visibility = TextView.GONE
            }
        } else {
            logStatusText.text = "Log collection is disabled. Enable it in Settings to collect logs."
            downloadLogsButton.isEnabled = false
            logFileInfoText.visibility = TextView.GONE
        }
    }

    private fun downloadLogFile() {
        try {
            val logFile = File(filesDir, "pangolin_go.log")
            
            if (!logFile.exists() || logFile.length() == 0) {
                Log.w(tag, "Log file does not exist or is empty")
                return
            }

            // Use FileProvider to share the file
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                logFile
            )

            // Create intent to share/save the file
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Pangolin Logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Save Logs"))
            Log.i(tag, "Log file shared successfully")

        } catch (e: Exception) {
            Log.e(tag, "Failed to share log file", e)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}