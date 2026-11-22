package com.arcu.attnd.ui

import com.arcu.attnd.data.AssistantAvailability
import com.arcu.attnd.data.AssistantDownload
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Gemini Nano's availability and its download, split out of [EventViewModel] (iOS:
 * `AppState.refreshOnDeviceModel`). The status is checked once an event is shown (never in screenshot
 * mode, [suspended]); the download runs only when the person asks for it.
 */
class OnDeviceModelController(
    private val scope: CoroutineScope,
    private val assistant: OnDeviceAssistant,
    private val suspended: () -> Boolean,
) {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    val isAvailable: Boolean get() = _state.value.availability == AssistantAvailability.AVAILABLE

    /** Checks whether the on-device model can be used (again while it is downloading). */
    fun check() {
        if (suspended()) return
        val current = _state.value.availability
        if (current != AssistantAvailability.UNKNOWN && current != AssistantAvailability.DOWNLOADING) return
        scope.launch {
            val availability = assistant.availability()
            AppLog.info(LogCategory.ASSISTANT, LogEvent.MODEL_STATUS, "Gemini Nano: $availability", mapOf("status" to availability))
            AppLog.info(LogCategory.PLANNER, LogEvent.PLANNER_AVAILABILITY, "Planner: $availability", mapOf("status" to availability))
            _state.update { it.copy(availability = availability) }
        }
    }

    /** Downloads Gemini Nano (only when the person asks for it). */
    fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _state.update { it.copy(availability = AssistantAvailability.DOWNLOADING, downloadError = null) }
            assistant.download().collect { status ->
                when (status) {
                    is AssistantDownload.Started -> AppLog.info(LogCategory.ASSISTANT, LogEvent.MODEL_DOWNLOAD, "Model download started", mapOf("state" to "started", "pct" to 0))
                    AssistantDownload.Completed -> AppLog.info(LogCategory.ASSISTANT, LogEvent.MODEL_DOWNLOAD, "Model download finished", mapOf("state" to "finished", "pct" to 100))
                    is AssistantDownload.Failed -> AppLog.warning(LogCategory.ASSISTANT, LogEvent.MODEL_DOWNLOAD, "Model download failed: ${status.message}", mapOf("state" to "failed"))
                    is AssistantDownload.Progress -> Unit
                }
                _state.update { a ->
                    when (status) {
                        is AssistantDownload.Started -> a.copy(totalBytes = status.totalBytes, downloadedBytes = 0)
                        is AssistantDownload.Progress -> a.copy(downloadedBytes = status.downloadedBytes)
                        AssistantDownload.Completed -> a.copy(availability = AssistantAvailability.AVAILABLE)
                        is AssistantDownload.Failed -> a.copy(availability = AssistantAvailability.DOWNLOADABLE, downloadError = status.message)
                    }
                }
            }
            // The flow can end without a final status: ask AICore again.
            if (_state.value.availability == AssistantAvailability.DOWNLOADING) {
                val availability = assistant.availability()
                _state.update { it.copy(availability = availability) }
            }
        }
    }
}
