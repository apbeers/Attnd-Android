package com.arcu.attnd.domain

/**
 * Whether this build should update (API-003, from `GET /api/app-status/`).
 * REQUIRED replaces the whole UI with the update screen; AVAILABLE shows a dismissible banner.
 */
enum class UpdateLevel { NONE, AVAILABLE, REQUIRED }

data class AppUpdate(
    val level: UpdateLevel = UpdateLevel.NONE,
    val latestVersion: String? = null,
    val updateUrl: String = PLAY_STORE_URL,
    val message: String? = null,
) {
    companion object {
        const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.arcu.attnd"
    }
}
