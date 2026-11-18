package com.nocrunch.rider.lifecycle

/**
 * Which engine process (and client) is the current one. Starts, restarts, crashes, startup failures and project close
 * all happen on different threads; going through this one lock means a start that was overtaken by any of them can't
 * install its client over a newer engine, and its late failure or exit can't tear down that newer engine.
 */
class EngineSlot<P : Any, C : Any> {
    private var process: P? = null
    private var currentClient: C? = null
    private var disposed = false

    val canStart: Boolean @Synchronized get() = !disposed && process == null

    /** The current engine's client, once it has started. */
    val client: C? @Synchronized get() = currentClient

    /** Makes [p] the current engine; false if one is already current or the project has closed. */
    @Synchronized
    fun begin(p: P): Boolean {
        if (!canStart) return false
        process = p
        return true
    }

    @Synchronized
    fun isCurrent(p: P): Boolean = !disposed && process === p

    /** Installs [c] as the client if [p] is still the current engine; false means [p] was superseded and should be stopped. */
    @Synchronized
    fun adopt(p: P, c: C): Boolean {
        if (!isCurrent(p)) return false
        currentClient = c
        return true
    }

    /** Drops [p] if it's still current (it exited or failed to start); false if something else already replaced it. */
    @Synchronized
    fun retire(p: P): Boolean {
        if (!isCurrent(p)) return false
        process = null
        currentClient = null
        return true
    }

    /** Drops whatever is current and returns it, for the caller to stop. */
    @Synchronized
    fun clear(): P? = process.also {
        process = null
        currentClient = null
    }

    /** Like [clear], and nothing can start afterwards. */
    @Synchronized
    fun dispose(): P? {
        disposed = true
        return clear()
    }
}
