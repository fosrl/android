package net.pangolin.Pangolin.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.MainActivity
import net.pangolin.Pangolin.util.TunnelManager

class PangolinTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateWatchJob: kotlinx.coroutines.Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watchState()
    }

    override fun onStopListening() {
        super.onStopListening()
        stateWatchJob?.cancel()
        stateWatchJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun watchState() {
        val tunnelManager = TunnelManager.getInstance() ?: run {
            setTileState(active = false, label = "Unavailable", clickable = false)

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("auto_connect", true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }

            return
        }

        stateWatchJob?.cancel()
        stateWatchJob = scope.launch {
            tunnelManager.tunnelState.collect { state ->
                setTileState(
                    active = state.isServiceRunning,
                    label = state.statusMessage,
                    clickable = state.canDisable || state.canEnable
                )
            }
        }
    }

    override fun onClick() {
        super.onClick()

        val tunnelManager = TunnelManager.getInstance() ?: return
        val state = tunnelManager.tunnelState.value

        if (!state.canDisable && !state.canEnable) {
            return
        }

        if (state.isServiceRunning || state.isConnecting) {
            scope.launch {
                tunnelManager.disconnect()
            }
        } else {
            scope.launch {
                tunnelManager.connect()
            }
        }
    }

    private fun setTileState(active: Boolean, label: String, clickable: Boolean) {
        val tile = qsTile ?: return

        tile.contentDescription = "Pangolin VPN"
        tile.icon = Icon.createWithResource(this, net.pangolin.Pangolin.R.drawable.ic_tile)

        tile.state = when {
            !clickable -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }

        tile.label = label

        tile.updateTile()
    }
}
