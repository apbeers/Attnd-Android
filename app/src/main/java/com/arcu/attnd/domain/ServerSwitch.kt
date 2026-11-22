package com.arcu.attnd.domain

/** The servers a test build can talk to (iOS `ServerSwitch`: "Test server: Production / Dev"). */
enum class TestServer(val host: String, val label: String, val logName: String) {
    PRODUCTION("attnd.app", "Production", "production"),
    DEV("dev.attnd.app", "Dev", "dev");

    companion object {
        fun of(host: String?): TestServer? = entries.firstOrNull { it.host == host }

        fun named(name: String?): TestServer? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The test server switch (iOS `ServerSwitch`, CI/CD → "Test server switch"). Only `BuildConfig.DEV` builds (`debug`,
 * `dev`) offer it: Options → About & support shows **Test server: Production / Dev**. The choice is stored and applies
 * at the **next launch** (the host is fixed per process: [resolve] runs once in `AttndApplication`); switching leaves
 * the current event (its `leave/` goes to the old server) and drops the old server's JWT. Store builds ignore any
 * stored choice, and the build still decides the default (`ATTND_SERVER`). Pure: `ServerSwitchTest`.
 */
object ServerSwitch {

    /** Whether this build offers the switch (iOS: TestFlight / Xcode installs; Android: dev and debug builds). */
    fun isSwitchable(devBuild: Boolean): Boolean = devBuild

    /** The host this process talks to: the stored choice in a switchable build, else the build's host. */
    fun resolve(buildHost: String, storedChoice: String?, devBuild: Boolean): String =
        if (!isSwitchable(devBuild)) buildHost else TestServer.named(storedChoice)?.host ?: buildHost

    /**
     * What Options shows: the server in use now and the one chosen for the next launch (null while they are the
     * same). A build host that is neither (a local server) shows as no selection until one is picked.
     */
    data class Choice(val current: TestServer?, val next: TestServer?) {
        val pendingRestart: Boolean get() = next != null && next != current
        val selected: TestServer? get() = next ?: current
    }

    fun choice(currentHost: String, storedChoice: String?): Choice {
        val current = TestServer.of(currentHost)
        val stored = TestServer.named(storedChoice)
        return Choice(current, stored?.takeIf { it != current })
    }

    /** Whether picking [target] changes anything (it leaves the event and is logged as `lifecycle.server_switch`). */
    fun isChange(currentHost: String, storedChoice: String?, target: TestServer): Boolean =
        choice(currentHost, storedChoice).selected != target

    /** The note under the switch once another server is chosen. */
    fun restartNote(target: TestServer): String = "Restart Attnd to use the ${target.label.lowercase()} server."
}
