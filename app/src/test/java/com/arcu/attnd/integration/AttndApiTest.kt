package com.arcu.attnd.integration

import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.NetworkModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.HttpException

class AttndApiTest {

    @Test
    fun testLookupEvent1() {
        println("Testing API Lookup with Key: ${BuildConfig.ATTND_API_KEY.take(5)}...")
        runBlocking {
            try {
                val event = NetworkModule.api.lookupEvent("event1")
                assertNotNull("Event should not be null", event)
                println("Successfully found event: ${event.name} (ID: ${event.id})")
            } catch (e: HttpException) {
                println("HTTP Error ${e.code()}: ${e.message()}")
                println("Response Body: ${e.response()?.errorBody()?.string()}")
                throw e
            } catch (e: Exception) {
                println("Generic API Error: ${e.message}")
                throw e
            }
        }
    }
}
