package com.flowclicker.app.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.job

/** Ownership covers a whole task/session/recording, not just one gesture. */
object ScreenControl {
    private val mutex = Mutex()
    @Volatile var owner: String? = null
        private set
    @Volatile private var ownerJob: Job? = null

    fun cancelActive() { ownerJob?.cancel() }

    suspend fun <T> withOwner(name: String, action: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        owner = name
        ownerJob = currentCoroutineContext().job
        try { action() } finally { ownerJob = null; owner = null }
    }
}
