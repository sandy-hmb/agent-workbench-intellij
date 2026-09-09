package org.agentworkbench.intellij

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

internal class RefreshCoordinator<T> {
    data class Resource<T>(val value: T?, val observedAt: Instant?, val error: String?, val generation: Long)
    private val resources = ConcurrentHashMap<String, Resource<T>>()
    private val generations = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong()
    private val slots = Semaphore(2)

    @Synchronized
    fun begin(key: String): Long = sequence.incrementAndGet().also { generations[key] = it }
    fun isCurrent(key: String, generation: Long): Boolean = generations[key] == generation
    fun current(key: String): Resource<T>? = resources[key]
    @Synchronized
    fun succeed(key: String, generation: Long, value: T, observedAt: Instant): Boolean {
        if (!isCurrent(key, generation)) return false
        resources[key] = Resource(value, observedAt, null, generation)
        return true
    }
    @Synchronized
    fun fail(key: String, generation: Long, message: String): Boolean {
        if (!isCurrent(key, generation)) return false
        val previous = resources[key]
        resources[key] = Resource(previous?.value, previous?.observedAt, message, generation)
        return true
    }
    fun <R> bounded(block: () -> R): R {
        slots.acquire()
        return try { block() } finally { slots.release() }
    }
    fun invalidate(key: String) { begin(key) }
    @Synchronized
    fun trim(maximum: Int) {
        require(maximum >= 0)
        while (generations.size > maximum) generations.keys.firstOrNull()?.let { generations.remove(it); resources.remove(it) } ?: return
        while (resources.size > maximum) resources.keys.firstOrNull()?.let(resources::remove) ?: return
    }
}
