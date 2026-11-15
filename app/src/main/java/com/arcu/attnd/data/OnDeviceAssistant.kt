package com.arcu.attnd.data

import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogLevel
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
            AppLog.caught(LogCategory.ASSISTANT, "genai.client", e, LogLevel.WARNING)
            clientFailed = true
            null
        } catch (e: LinkageError) {
            // Play services / AICore classes missing on this build of the OS.
            AppLog.caught(LogCategory.ASSISTANT, "genai.client", e, LogLevel.WARNING)
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
            AppLog.caught(LogCategory.ASSISTANT, "genai.status", e, LogLevel.WARNING)
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
    suspend fun answer(prompt: String): String = generate(prompt, TEMPERATURE, MAX_OUTPUT_TOKENS)

    /**
     * The planner's preference extraction ([com.arcu.attnd.domain.PlanRequest.prompt]): one JSON
     * object, read and validated by `PlanRequest.parse`. Deterministic (temperature 0). Throws on
     * failure; the caller falls back to the plain-rule reading.
     */
    suspend fun extractPreferences(prompt: String): String = generate(prompt, 0f, MAX_JSON_TOKENS)

    /**
     * One event-assistant turn ([com.arcu.attnd.domain.AssistantModel.prompt]): the reply JSON, streamed.
     * Emits the reply so far (accumulated) after each chunk, so complete blocks can be shown early
     * ([com.arcu.attnd.domain.AssistantModel.completeBlocks]); the last emission is the whole reply.
     * Throws on failure; the caller falls back to the rule-based router.
     */
    fun turn(prompt: String): Flow<String> = flow {
        val model = model() ?: throw IllegalStateException("On-device AI isn't available on this device.")
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = TURN_TEMPERATURE
            topK = TOP_K
            maxOutputTokens = MAX_TURN_TOKENS
        }
        var text = ""
        model.generateContentStream(request).collect { chunk ->
            val part = chunk.candidates.firstOrNull()?.text.orEmpty()
            // Chunks are deltas; tolerate an implementation that resends the whole text so far.
            text = if (part.startsWith(text) && text.isNotEmpty()) part else text + part
            emit(text)
        }
    }

    private suspend fun generate(prompt: String, temperature: Float, maxTokens: Int): String {
        val model = model() ?: throw IllegalStateException("On-device AI isn't available on this device.")
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                topK = TOP_K
                maxOutputTokens = maxTokens
            }
        )
        return response.candidates.firstOrNull()?.text.orEmpty().trim()
    }

    private companion object {
        const val TEMPERATURE = 0.2f
        const val TOP_K = 16
        const val MAX_OUTPUT_TOKENS = 256
        const val MAX_JSON_TOKENS = 320
        const val TURN_TEMPERATURE = 0.1f
        const val MAX_TURN_TOKENS = 512
    }
}
