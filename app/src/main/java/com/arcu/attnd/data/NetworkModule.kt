package com.arcu.attnd.data

import android.content.Context
import com.arcu.attnd.BuildConfig
import com.arcu.attnd.util.integrity.AttestationManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkModule {
    private const val BASE_URL = "https://attnd.app/"

    private var _api: AttndApi? = null

    val api: AttndApi
        get() = _api ?: error("NetworkModule.init(context) must be called before accessing api")

    /**
     * Call once in MainActivity.onCreate() before any API access.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init(context: Context) {
        if (_api != null) return
        _api = buildApi(context.applicationContext)
    }

    private fun buildApi(context: Context): AttndApi {
        val authInterceptor = Interceptor { chain ->
            val token = runBlocking { AttestationManager.getToken() } ?: BuildConfig.ATTND_API_KEY
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("X-Requested-With", "com.arcu.attnd")
                .build()
            chain.proceed(request)
        }

        val tokenAuthenticator = object : Authenticator {
            // OkHttp calls this when a response has status 401.
            // Returning null tells OkHttp to not retry.
            override fun authenticate(route: Route?, response: Response): Request? {
                // Avoid infinite retry loops — give up if we've already retried
                if (responseCount(response) >= 2) return null

                // Clear stale JWT and re-attest
                AttestationManager.invalidateToken()
                val newToken = runBlocking { AttestationManager.getToken() }
                    ?: return null   // Play Integrity unavailable — give up

                return response.request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
            }

            private fun responseCount(response: Response): Int {
                var count = 1
                var prior = response.priorResponse
                while (prior != null) {
                    count++
                    prior = prior.priorResponse
                }
                return count
            }
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AttndApi::class.java)
    }
}
