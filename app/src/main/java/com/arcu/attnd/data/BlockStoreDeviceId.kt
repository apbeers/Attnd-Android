package com.arcu.attnd.data

import android.content.Context
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DeviceIdentity
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogLevel
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The device id's copy in Play services' Block Store, which outlives an uninstall on the same
 * phone. Stored with cloud backup off ([StoreBytesData.Builder.setShouldBackupToCloud]), so it stays
 * on this phone like iOS's this-device-only Keychain item: a new phone never gets another phone's id.
 * Every failure (no Play services, an old Play services) is null / false: the id then lives in the
 * app's own preferences only, as before.
 */
class BlockStoreDeviceId(context: Context) {
    private val client = Blockstore.getClient(context.applicationContext)

    suspend fun read(): String? = try {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(DeviceIdentity.BLOCK_STORE_KEY)).build()
        val bytes = client.retrieveBytes(request).await().blockstoreDataMap[DeviceIdentity.BLOCK_STORE_KEY]?.bytes
        DeviceIdentity.valid(bytes?.toString(Charsets.UTF_8))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.caught(LogCategory.STORAGE, "blockstore.read", e, LogLevel.NOTICE)
        null
    }

    suspend fun write(id: String): Boolean = try {
        val data = StoreBytesData.Builder()
            .setKey(DeviceIdentity.BLOCK_STORE_KEY)
            .setBytes(id.toByteArray(Charsets.UTF_8))
            .setShouldBackupToCloud(false)
            .build()
        client.storeBytes(data).await()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.caught(LogCategory.STORAGE, "blockstore.write", e, LogLevel.NOTICE)
        false
    }
}
