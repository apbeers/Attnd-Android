package com.arcu.attnd.util.integrity

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.attest.AndroidAttestRequest
import com.arcu.attnd.data.attest.AttestationApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
 * and NetworkModule falls back to the static ATTND_API_KEY.
 *
 * Thread safety: a Mutex ensures only one attestation flow runs at a time even
 * when multiple coroutines call getToken() concurrently.
 */
object AttestationManager {

    private const val TAG = "AttestationManager"
    private const val PREFS_NAME = "attnd_attestation"
    private const val KEY_JWT = "jwt"
    private const val BASE_URL = "https://attnd.app/"

    // Expires 60 s early to handle clock skew and propagation lag
    private const val EXPIRY_BUFFER_SECONDS = 60L

    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    // Unauthenticated Retrofit for the attest endpoints (no auth header needed)
    private val attestApi: AttestationApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AttestationApi::class.java)
    }

    // MARK: - Public API

    fun init(context: Context) {
        appContext = context.applicationContext
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
            runCatching { attest(ctx) }
                .onFailure { Log.w(TAG, "Attestation failed: ${it.message}") }
                .getOrNull()
        }
    }

    /** Clear the cached JWT (call after receiving a 401 to force re-attestation). */
    fun invalidateToken() {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { remove(KEY_JWT) }
    }

    // MARK: - Attestation flow

    private suspend fun attest(context: Context): String {
        val deviceId = deviceId(context)

        // 1. Fetch nonce
        val nonce = attestApi.getNonce().nonce
        Log.d(TAG, "Nonce fetched")

        // 2. Request Play Integrity token
        val integrityMgr = IntegrityManager(context)
        val tokenResult = integrityMgr.fetchIntegrityToken(nonce)
        val integrityToken = tokenResult.getOrElse {
            throw AttestationException("Play Integrity token unavailable: ${it.message}", it)
        }
        Log.d(TAG, "Integrity token obtained")

        // 3. Send to backend
        val response = attestApi.attestAndroid(
            AndroidAttestRequest(
                device_id = deviceId,
                token = integrityToken,
                nonce = nonce,
            )
        )
        val jwt = response.token
        Log.i(TAG, "Attestation complete, JWT received")

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
        } catch (e: Exception) {
            false
        }
    }

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("attnd_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null)
            ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit { putString("device_id", it) }
            }
    }
}

class AttestationException(message: String, cause: Throwable? = null) : Exception(message, cause)
