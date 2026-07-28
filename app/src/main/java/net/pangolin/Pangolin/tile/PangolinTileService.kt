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

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onClick() {
        super.onClick()

        val tunnelManager = TunnelManager.getInstance() ?: return

        val state = tunnelManager.tunnelState.value

        if(state.isServiceRunning) {
            scope.launch {
                tunnelManager.disconnect()
            }

            setTileState(active = true)
        } else {
            scope.launch {
                tunnelManager.connect()
            }

            setTileState(active = false)
        }
    }

    private fun updateTile() {
        val tunnelManager = TunnelManager.getInstance() ?: return

        setTileState(tunnelManager.connectionStatus.value?.connected)
    }

    private fun setTileState(active: Boolean?) {
        val tile = qsTile ?: return

        tile.contentDescription = "Pangolin VPN"
        tile.icon = Icon.createWithResource(this, net.pangolin.Pangolin.R.drawable.ic_launcher_foreground)

        tile.state = when(active) {
            true -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }

        tile.label = when(active) {
            true -> "Connected"
            false -> "Disconnected"
            else -> "Unknown"
        }

        tile.updateTile()
    }
}