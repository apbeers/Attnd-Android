package com.arcu.attnd.util.integrity

import android.content.Context
import androidx.core.content.edit
import com.arcu.attnd.data.attest.AndroidAttestRequest
import com.arcu.attnd.data.attest.AttestationApi
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Base64

/**
 * Manages the Google Play Integrity attestation flow and the resulting JWT lifecycle.
 *
 * Flow
 * ----
 * 1. Fetch a one-time nonce from GET /api/v1/attest/nonce/
 * 2. Request a Play Integrity token from IntegrityManager using that nonce
 * 3. POST {device_id, token, nonce} to POST /api/v1/attest/android/
 * 4. Store the returned JWT in SharedPreferences and return it
 *
 * Subsequent calls return the cached JWT until it expires or is invalidated.
 * If Play Integrity is unavailable (emulator, no Play Services), returns null
 * and AttestedTokenProvider falls back to the static ATTND_API_KEY. After a failure,
 * attestation is not retried for [FAILURE_BACKOFF_MS] so every request doesn't pay for it.
 *
 * Thread safety: a Mutex ensures only one attestation flow runs at a time even
 * when multiple coroutines call getToken() concurrently.
 */
object AttestationManager {

    private const val PREFS_NAME = "attnd_attestation"
    private const val KEY_JWT = "jwt"
    private const val FAILURE_BACKOFF_MS = 10 * 60 * 1000L

    // Expires 60 s early to handle clock skew and propagation lag
    private const val EXPIRY_BUFFER_SECONDS = 60L

    private var appContext: Context? = null
    private var deviceIdProvider: () -> String = { "" }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val attestApi = AttestationApi()

    @Volatile
    private var lastFailureAt = 0L

    // MARK: - Public API

    /** Call once from Application.onCreate(), before any API request. */
    fun init(context: Context, deviceId: () -> String) {
        appContext = context.applicationContext
        deviceIdProvider = deviceId
    }

    /**
     * Kick off attestation in the background at app start so the JWT is warm
     * before the first real API call needs it.
     */
    fun warmup() {
        scope.launch { runCatching { getToken() } }
    }

    /**
     * Returns a valid JWT, running attestation if the cache is empty or stale.
     * Returns null if Play Integrity is unavailable (emulator / no Play Services).
     * Callers that get null should fall back to the static API key.
     */
    suspend fun getToken(): String? {
        val ctx = appContext ?: return null

        // Fast path — cached, non-expired token
        cachedToken(ctx)?.let { return it }

        // Serialize concurrent callers so only one attestation runs
        return mutex.withLock {
            // Check again after acquiring lock — another coroutine may have attested
            cachedToken(ctx)?.let { return@withLock it }
            if (System.currentTimeMillis() - lastFailureAt < FAILURE_BACKOFF_MS) return@withLock null
            runCatching { attest(ctx) }
                .onFailure {
                    lastFailureAt = System.currentTimeMillis()
                    AppLog.warning(
                        LogCategory.AUTH, LogEvent.ATTEST_FAILED, "Attestation failed; static key fallback, retried in 10 min",
                        mapOf(
                            "kind" to "play_integrity", "error" to AppLog.errorKind(it), "domain" to it.javaClass.simpleName,
                            "retry_after_s" to FAILURE_BACKOFF_MS / 1000,
                        ),
                    )
                }
                .getOrNull()
        }
    }

    /** Clear the cached JWT (call after receiving a 401 to force re-attestation). */
    fun invalidateToken() {
        AppLog.info(LogCategory.AUTH, LogEvent.TOKEN_EXPIRED, "Stored attestation token dropped", mapOf("reason" to "401"))
        lastFailureAt = 0L
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { remove(KEY_JWT) }
    }

    // MARK: - Attestation flow

    private suspend fun attest(context: Context): String {
        val deviceId = deviceIdProvider()

        // 1. Fetch nonce
        val nonce = attestApi.nonce()
        val started = System.nanoTime()
        AppLog.info(LogCategory.AUTH, LogEvent.ATTEST_STARTED, "Attestation started", mapOf("kind" to "play_integrity"))

        // 2. Request Play Integrity token
        val integrityMgr = IntegrityManager(context)
        val tokenResult = integrityMgr.fetchIntegrityToken(nonce)
        val integrityToken = tokenResult.getOrElse {
            throw AttestationException("Play Integrity token unavailable: ${it.message}", it)
        }

        // 3. Send to backend
        val jwt = attestApi.attestAndroid(
            AndroidAttestRequest(
                deviceId = deviceId,
                token = integrityToken,
                nonce = nonce,
            )
        )
        AppLog.info(
            LogCategory.AUTH, LogEvent.ATTEST_FINISHED, "Attestation complete",
            mapOf("kind" to "play_integrity", "ms" to (System.nanoTime() - started) / 1_000_000, "ok" to true),
        )

        // 4. Persist
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_JWT, jwt)
        }
        return jwt
    }

    // MARK: - Helpers

    /** Returns stored JWT if present and not within EXPIRY_BUFFER_SECONDS of expiry. */
    private fun cachedToken(context: Context): String? {
        val jwt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JWT, null) ?: return null
        return if (isJwtValid(jwt)) jwt else null
    }

    /**
     * Decode the JWT payload (no signature verification — we trust our own backend)
     * and check whether the `exp` claim is still in the future.
     */
    private fun isJwtValid(jwt: String): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return false
            val payload = parts[1]
                .replace('-', '+').replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            val json = JSONObject(String(Base64.getDecoder().decode(payload)))
            val exp = json.getLong("exp")
            System.currentTimeMillis() / 1000 < exp - EXPIRY_BUFFER_SECONDS
        } catch (_: Exception) {
            false
        }
    }
}

class AttestationException(message: String, cause: Throwable? = null) : Exception(message, cause)
