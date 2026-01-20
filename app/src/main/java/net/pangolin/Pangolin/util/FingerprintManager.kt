package net.pangolin.Pangolin.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.util.SocketManager

class FingerprintManager(
    private val socketManager: SocketManager,
    private val collector: AndroidFingerprintCollector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(intervalSeconds: Long = 30) {
        if (job?.isActive == true) return

        job = scope.launch {
            while (isActive) {
                runUpdateMetadata()
                delay(intervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runUpdateMetadata() {
        try {
            val fingerprint = collector.gatherFingerprintInfo()
            val postures = collector.gatherPostureChecks()

            socketManager.updateMetadata(
                fingerprint = fingerprint,
                postures = postures
            )
        } catch (e: Exception) {
            Log.w("FingerprintManager", "Failed to push fingerprint/postures", e)
        }
    }
}
