package com.arcu.attnd.data.net

import com.arcu.attnd.BuildConfig

/**
 * The API host this process talks to: `BuildConfig.ATTND_SERVER`, or in dev and debug builds the test server chosen
 * in Options ([com.arcu.attnd.domain.ServerSwitch.resolve], set once by `AttndApplication` before anything is
 * built). Fixed for the life of the process: a new choice applies at the next launch.
 */
object ServerHost {
    @Volatile
    var host: String = BuildConfig.ATTND_SERVER
        private set

    fun install(resolved: String) {
        host = resolved
    }

    val origin: String get() = "https://$host"
}
