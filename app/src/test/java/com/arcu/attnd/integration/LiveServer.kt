package com.arcu.attnd.integration

import com.arcu.attnd.data.api.ApiJson
import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.api.AuthTokenProvider
import com.arcu.attnd.data.net.HttpClient
import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.data.net.UrlConnectionHttpClient
import com.arcu.attnd.domain.Event
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assume.assumeTrue
import java.util.Collections
import java.util.UUID

/**
 * Configuration and helpers for the live integration tests (`com.arcu.attnd.integration`).
 *
 * The server and key come from the environment, never from BuildConfig, so the tests can point at
 * dev or production without rebuilding the app:
 *   ATTND_IT_SERVER  host, e.g. `attnd-dev-7ab893f37020.herokuapp.com` or `attnd.app`
 *                    (an `https://…` / `http://…` origin also works, e.g. a local runserver)
 *   ATTND_IT_KEY     bearer key the server accepts (dev: its static key; prod: the CI key that only
 *                    works for the DEMO event)
 * When either is missing every test is skipped (JUnit assumption), so `./gradlew test` stays green
 * offline. Run them with `./gradlew testDebugUnitTest -PintegrationTests` (see app/build.gradle).
 *
 * Every call goes through the app's real stack: [UrlConnectionHttpClient] → [AttndApi] → [ApiMapper].
 */
object LiveServer {

    val server: String = System.getenv("ATTND_IT_SERVER").orEmpty().trim().trimEnd('/')
    val key: String = System.getenv("ATTND_IT_KEY").orEmpty().trim()

    val configured: Boolean get() = server.isNotEmpty() && key.isNotEmpty()

    /** `https://<host>` (or the origin given with a scheme). */
    val origin: String get() = if ("://" in server) server else "https://$server"

    fun baseUrl(apiVersion: Int = AttndApi.API_VERSION): String = "$origin/api/v$apiVersion/"

    /** Skips the calling test when the environment doesn't name a server and key. */
    fun assumeConfigured() {
        assumeTrue("ATTND_IT_SERVER / ATTND_IT_KEY not set; skipping live API tests", configured)
    }

    /** The DEMO event's code (any case and surrounding whitespace finds it). */
    const val DEMO_CODE = "DEMO"

    /** Every request body the tests sent, for client-side contract checks (e.g. never `"push_token": ""`). */
    val sentBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The real HTTP client, recording request bodies. */
    val http: HttpClient = RecordingHttpClient(UrlConnectionHttpClient())

    /** A token provider like the app's static-key fallback; [fresh] is what a re-attestation would return. */
    class Tokens(private val token: String, private val fresh: String? = null) : AuthTokenProvider {
        var reauthCount = 0
            private set

        override suspend fun token(): String = token
        override suspend fun reauthenticate(): String? {
            reauthCount++
            return fresh
        }
    }

    /** The client the app would build, pointed at the integration server. */
    fun api(token: String = key, fresh: String? = null, apiVersion: Int = AttndApi.API_VERSION): AttndApi =
        AttndApi(Tokens(token, fresh), baseUrl(apiVersion), http)

    /** The DEMO event, looked up once per test JVM. */
    val demo: Event by lazy {
        runBlocking {
            ApiMapper.event(api().lookup(DEMO_CODE).event) ?: error("lookup($DEMO_CODE) returned no usable event")
        }
    }

    /**
     * `ci-android-<run id>-<random>`: recognisable on the server, unique per test so parallel runs
     * (dev and prod jobs, re-runs) never share a device.
     */
    fun newDeviceId(): String {
        val run = System.getenv("GITHUB_RUN_ID")?.takeIf { it.isNotBlank() } ?: "local"
        return "ci-android-$run-${UUID.randomUUID().toString().take(8)}"
    }

    fun parse(json: String): JsonElement = ApiJson.parseToJsonElement(json)

    private class RecordingHttpClient(private val inner: HttpClient) : HttpClient {
        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse {
            if (body != null) sentBodies += body
            return inner.execute(method, url, headers, body)
        }
    }
}
