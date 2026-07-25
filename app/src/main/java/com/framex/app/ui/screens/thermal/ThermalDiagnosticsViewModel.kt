package com.framex.app.ui.screens.thermal

import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framex.app.metrics.MetricsEngine
import com.framex.app.metrics.MetricsState
import com.framex.app.metrics.SessionLogger
import com.framex.app.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ThermalDiagnosticsViewModel @Inject constructor(
    private val metricsEngine: MetricsEngine,
    private val sessionLogger: SessionLogger,
    private val shizukuManager: ShizukuManager
) : ViewModel() {

    val metricsState = metricsEngine.metricsState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MetricsState())

    val snapshotHistory = metricsEngine.snapshotHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isRecording = sessionLogger.isRecording
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isShizukuAvailable = shizukuManager.isShizukuAvailable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasShizukuPermission = shizukuManager.hasPermission
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        metricsEngine.setScreenOverrideModules(
            setOf("thermal", "temp", "top_process"),
            requesterKey = "thermal_diagnostics_screen"
        )
    }

    override fun onCleared() {
        super.onCleared()
        metricsEngine.setScreenOverrideModules(emptySet(), requesterKey = "thermal_diagnostics_screen")
    }

    fun toggleRecording() {
        if (sessionLogger.isRecording.value) {
            sessionLogger.stopRecording()
        } else {
            sessionLogger.startRecording()
        }
    }

    fun recordedSampleCount(snapshots: List<MetricsEngine.MetricsSnapshot>): Int {
        val startIndex = sessionLogger.recordingStartIndex
        return (snapshots.size - startIndex).coerceAtLeast(0)
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun refreshShizukuState() {
        shizukuManager.refreshState()
    }

    fun exportAndShare(onReady: (Intent) -> Unit, onEmpty: () -> Unit) {
        viewModelScope.launch {
            val file = sessionLogger.exportToFile()
            if (file == null) {
                onEmpty()
            } else {
                onReady(sessionLogger.buildShareIntent(file))
            }
        }
    }

    fun buildDiagnosticSummaryText(snapshots: List<MetricsEngine.MetricsSnapshot>): String {
        val state = metricsState.value
        val recent = snapshots.takeLast(60)
        val maxCpu = recent.maxOfOrNull { it.state.thermalCpuC } ?: state.thermalCpuC
        val maxSkin = recent.maxOfOrNull { it.state.thermalSkinC } ?: state.thermalSkinC
        val avgFps = if (recent.isNotEmpty()) recent.map { it.state.fps }.average().toInt() else state.fps
        val minFps = recent.minOfOrNull { it.state.fps } ?: state.fps
        val maxJank = recent.maxOfOrNull { it.state.jankyFrames } ?: state.jankyFrames
        val topProcess = state.topProcessName ?: "None"

        val statusText = getThermalStatusLabel(state.thermalStatus)

        return """
### FrameX Thermal Diagnostic Summary
- **Device**: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})
- **System Thermal State**: $statusText
- **CPU Peak (60s)**: ${String.format(Locale.US, "%.1f°C", maxCpu)}
- **Skin Peak (60s)**: ${String.format(Locale.US, "%.1f°C", maxSkin)}
- **FPS Avg / Min**: $avgFps / $minFps FPS
- **Jank Peak**: $maxJank frames
- **Top Process**: $topProcess (${String.format(Locale.US, "%.0f%%", state.topProcessCpuPercent)})
""".trimIndent()
    }
}
