package com.arcu.attnd.data

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Whether Gemini Nano can answer on this device (ML Kit GenAI `FeatureStatus`). */
enum class AssistantAvailability { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** Model download progress. */
sealed interface AssistantDownload {
    data class Started(val totalBytes: Long) : AssistantDownload
    data class Progress(val downloadedBytes: Long) : AssistantDownload
    data object Completed : AssistantDownload
    data class Failed(val message: String) : AssistantDownload
}

/**
 * "Ask about this event" on the device: Gemini Nano through the ML Kit GenAI Prompt API
 * (`com.google.mlkit:genai-prompt`, **beta**). The prompt is built locally
 * ([com.arcu.attnd.domain.AssistantPrompt]) from the joined event's bundle and only ever goes to the
 * on-device model (AICore); nothing here calls a server.
 *
 * Every call is guarded: devices without AICore / Gemini Nano report [AssistantAvailability.UNAVAILABLE]
 * and the search screen stays a plain search. The client is created lazily, the first time the
 * search screen asks for the status, so app start never binds to AICore.
 */
class OnDeviceAssistant {

    private var client: GenerativeModel? = null
    private var clientFailed = false

    @Synchronized
    private fun model(): GenerativeModel? {
        if (clientFailed) return null
        client?.let { return it }
        return try {
            Generation.getClient().also { client = it }
        } catch (e: Exception) {
            Log.w(TAG, "Prompt API client unavailable", e)
            clientFailed = true
            null
        } catch (e: LinkageError) {
            // Play services / AICore classes missing on this build of the OS.
            Log.w(TAG, "Prompt API client unavailable", e)
            clientFailed = true
            null
        }
    }

    suspend fun availability(): AssistantAvailability {
        val model = model() ?: return AssistantAvailability.UNAVAILABLE
        return try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> AssistantAvailability.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> AssistantAvailability.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> AssistantAvailability.DOWNLOADING
                else -> AssistantAvailability.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Prompt API status check failed", e)
            AssistantAvailability.UNAVAILABLE
        }
    }

    /** Downloads Gemini Nano (AICore manages the files). Emits [AssistantDownload.Failed] instead of throwing. */
    fun download(): Flow<AssistantDownload> {
        val model = model() ?: return flow { emit(AssistantDownload.Failed("On-device AI isn't available on this device.")) }
        return model.download()
            .map { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> AssistantDownload.Started(status.bytesToDownload)
                    is DownloadStatus.DownloadProgress -> AssistantDownload.Progress(status.totalBytesDownloaded)
                    is DownloadStatus.DownloadFailed -> AssistantDownload.Failed(status.e.message ?: "Download failed.")
                    else -> AssistantDownload.Completed
                }
            }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(AssistantDownload.Failed(e.message ?: "Download failed."))
            }
    }

    /** Runs [prompt] on the device. Throws on failure (quota, model busy, …); callers fall back to search. */
    suspend fun answer(prompt: String): String {
        val model = model() ?: throw IllegalStateException("On-device AI isn't available on this device.")
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = TEMPERATURE
                topK = TOP_K
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
        )
        return response.candidates.firstOrNull()?.text.orEmpty().trim()
    }

    private companion object {
        const val TAG = "OnDeviceAssistant"
        const val TEMPERATURE = 0.2f
        const val TOP_K = 16
        const val MAX_OUTPUT_TOKENS = 256
    }
}
