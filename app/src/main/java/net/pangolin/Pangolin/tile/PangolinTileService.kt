package net.pangolin.Pangolin.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    private fun watchState() {
        val tunnelManager = TunnelManager.getInstance() ?: run {
            setTileState(active = false, label = "Disconnected", clickable = true)
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
        tile.icon = Icon.createWithResource(this, net.pangolin.Pangolin.R.drawable.ic_launcher_foreground)

        tile.state = when {
            !clickable -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }

        tile.label = label

        tile.updateTile()
    }
}
