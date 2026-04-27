package com.arcu.attnd.data.prefs

import android.content.Context
import java.util.UUID
import androidx.core.content.edit

class DevicePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("attnd_prefs", Context.MODE_PRIVATE)

    fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit { putString("device_id", id) }
        }
        return id
    }

    fun saveEventCode(code: String) {
        prefs.edit { putString("event_code", code) }
    }

    fun getEventCode(): String? {
        return prefs.getString("event_code", null)
    }

    fun clearEventCode() {
        prefs.edit { remove("event_code") }
    }
}
