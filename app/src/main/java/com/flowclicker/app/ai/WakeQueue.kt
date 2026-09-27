package com.flowclicker.app.ai

/** Bounded waiting work with deduplication that includes the in-flight item. */
class WakeQueue<T>(private val capacity: Int, private val key: (T) -> String) {
    private val waiting = ArrayDeque<T>()
    private val keys = mutableSetOf<String>()
    @Synchronized fun offer(item: T): Boolean {
        if (key(item) in keys || waiting.size >= capacity) return false
        keys.add(key(item))
        waiting.addLast(item)
        return true
    }
    @Synchronized fun poll(): T? = waiting.removeFirstOrNull()
    @Synchronized fun finish(item: T) { keys.remove(key(item)) }
    @Synchronized fun clear() { waiting.clear(); keys.clear() }
    @Synchronized fun size(): Int = waiting.size
}

/** Same-task repetitions do not satisfy a wait for a different successor. */
class StallWatch {
    private val deadlines = mutableMapOf<Long, Long>()
    @Synchronized fun fired(id: Long) { deadlines.keys.removeAll { it != id } }
    @Synchronized fun finished(id: Long, deadline: Long) { deadlines.putIfAbsent(id, deadline) }
    @Synchronized fun due(now: Long): List<Long> {
        val ids = deadlines.filterValues { it <= now }.keys.toList()
        ids.forEach { deadlines.remove(it) }
        return ids
    }
    @Synchronized fun clear() { deadlines.clear() }
}
