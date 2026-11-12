# Code Review: `SimpleCache`

Context: in-memory item cache, thousands of reads/s, hundreds of writes/s, tens of threads.

`ConcurrentHashMap` makes individual `put`/`get` thread-safe, so there is no data race in the basic operations. The problems are in what the class does around it. Ordered by severity.

## Blockers

### 1. Expired entries are never removed (memory leak)
`get()` only *ignores* expired entries; nothing ever removes them. An entry that is written once and never read again stays in the map forever. With a high-cardinality key space and hundreds of writes/s, the map grows without bound until the process hits OOM or spends its time in GC.
**Fix:** remove on read when expired, and add proactive cleanup (a scheduled sweeper, or use a library with built-in expiry such as Caffeine).

### 2. No size bound or eviction policy
Even with TTL cleanup, a burst of distinct keys within one minute can exhaust the heap: at 500 writes/s with 60 s TTL that is ~30k live entries *per unique-key stream*, and the value sizes are unknown. There is no max size and no LRU/LFU eviction.
**Fix:** enforce a maximum size with an eviction policy.

### 3. Cache stampede (thundering herd)
When a hot key expires, thousands of reads/s all see `null` at once and all go to the backing store to reload, then all `put`. There is no atomic `getOrLoad`/`computeIfAbsent`-style API, so callers cannot coalesce loads. This can overload the database exactly when the key is hottest.
**Fix:** offer `get(key) { loader }` backed by `compute`/per-key futures, plus jittered TTLs and optionally refresh-ahead / stale-while-revalidate.

## Major

### 4. `size()` is misleading
It returns the raw map size, which includes expired but not yet purged entries (see #1), so it over-reports the number of usable items. Also, `ConcurrentHashMap.size` is only an estimate under concurrent updates. Anyone using it for metrics, capacity checks or tests will get wrong answers.

### 5. Wall-clock time used for expiry
`System.currentTimeMillis()` is not monotonic. NTP adjustments, manual clock changes or VM pauses can make `now - timestamp` negative (entries live far longer than TTL) or jump forward (mass early expiry).
**Fix:** use `System.nanoTime()` (monotonic) for elapsed-time measurement, behind an injectable `Clock`/time source so tests are deterministic.

### 6. Check-then-act race when removing stale entries (if fixed naively)
The natural fix for #1 is `cache.remove(key)` after seeing an expired entry. That is itself racy: between the read and the remove, another thread may `put` a fresh value, which then gets deleted. The removal must be conditional on the exact stale entry: `cache.remove(key, entry)` (or done inside `compute`).

### 7. Null handling is unsafe and ambiguous
- `K` and `V` are unbounded Kotlin type parameters, so they can be nullable types (`SimpleCache<String?, Int?>`). `ConcurrentHashMap` rejects null keys with an NPE at runtime, and the `CacheEntry` boxes the value so null values are "allowed" by the compiler.
- `get()` returns `V?`, so a miss, an expired entry, and a legitimately cached `null` are indistinguishable. Negative caching and "not found" semantics become impossible.
**Fix:** `K : Any, V : Any`, or return an explicit result type / use a sentinel for negative entries.

## Minor / design

### 8. TTL is hard-coded and is write-time only
`ttlMs` is a private constant: not configurable per cache or per entry. It is also an `Int` compared against a `Long`, which works but invites overflow bugs if someone raises it (`Int` overflows around 24.8 days in ms). No expire-after-access option. Make it a `Long`/`Duration` constructor parameter.

### 9. Missing basic API
No `remove`/`invalidate`, `clear`, bulk operations or `putIfAbsent`. Stale data after an update to the source of truth can only be fixed by waiting for TTL (or by overwriting).

### 10. Leaky internals
`CacheEntry` is public, so callers can depend on it. Make it `private`/`internal`.

### 11. No observability
No hit/miss/eviction/load counters, so there is no way to tune TTL or size, or to detect that the cache is useless or thrashing in production.

### 12. No tests
No unit tests for expiry boundaries (`< ttl`), concurrency, or size behaviour; and with `currentTimeMillis` hard-wired in, expiry cannot be tested without sleeping.

### 13. Mutable cached values
If `V` is mutable, all threads share the same instance. Concurrent mutation by callers is not protected by the cache. Document that values must be immutable (or copy defensively).

## Recommendation
For production, prefer a battle-tested library (Caffeine: bounded size, `expireAfterWrite`, async refresh, stats, stampede protection). If it must be hand-written, a minimal improved version:

```kotlin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SimpleCache<K : Any, V : Any>(
    private val ttlNanos: Long,
    private val maxSize: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {

    private class Entry<V>(val value: V, val expiresAt: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()
    private val sweeper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cache-sweeper").apply { isDaemon = true }
    }.also { it.scheduleWithFixedDelay(::sweep, 10, 10, TimeUnit.SECONDS) }

    fun put(key: K, value: V) {
        if (map.size >= maxSize && !map.containsKey(key)) evictSome()
        map[key] = Entry(value, nanoTime() + ttlNanos)
    }

    fun get(key: K): V? {
        val e = map[key] ?: return null
        if (nanoTime() - e.expiresAt < 0) return e.value
        map.remove(key, e) // conditional: never removes a fresher value
        return null
    }

    /** Atomic load: one loader call per key even under contention. */
    fun getOrLoad(key: K, loader: (K) -> V): V =
        map.compute(key) { k, old ->
            if (old != null && nanoTime() - old.expiresAt < 0) old
            else Entry(loader(k), nanoTime() + ttlNanos)
        }!!.value

    fun invalidate(key: K) { map.remove(key) }

    /** Approximate number of live (non-expired) entries. */
    fun size(): Int {
        val now = nanoTime()
        return map.values.count { now - it.expiresAt < 0 }
    }

    private fun sweep() {
        val now = nanoTime()
        map.entries.removeIf { now - it.value.expiresAt >= 0 }
    }

    private fun evictSome() {
        sweep()
        // Still full: drop arbitrary entries. A real implementation should use LRU/LFU.
        val it = map.entries.iterator()
        while (map.size >= maxSize && it.hasNext()) { it.next(); it.remove() }
    }

    override fun close() { sweeper.shutdownNow() }
}
```

Caveats of the sketch: `compute` holds a bin lock while the loader runs (keep loaders fast, or use per-key futures); size enforcement is approximate under races; arbitrary eviction is not LRU.
