package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Activity
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Intensity
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiContextLlm
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

/**
 * Proves the two compound operations in `ContextManager` are now one step.
 *
 * Both used to be a `filter { }` followed by a `forEach { remove }`. A `ConcurrentHashMap` made each
 * single call safe, but it could not make a pair of them safe, so another thread could change the map
 * between the two halves. The map is now plain and guarded by one
 * [app.aaps.core.interfaces.concurrent.AapsLock], and both halves run inside one
 * [app.aaps.core.interfaces.concurrent.withLock] block.
 *
 * The first two tests drive a real race from two threads. They are written so that the assertion is
 * exact - the failure is a wrong count or a missing intent, not a timing guess - but they do need
 * repetition to meet the window, which is why they run many rounds. Both were checked against a
 * deliberately re-split version of the code, where they fail.
 *
 * The third test is deterministic and guards the other half of the fix: the lock must NOT be held
 * while the storage write runs, because that write calls out of this class.
 */
class ContextManagerAtomicityTest {

    /** Enough rounds to meet the race window, few enough to keep the test quick. */
    private val rounds = 400

    /** Ids touched per round. */
    private val perRound = 6

    /** The id race needs a fresh manager each round, so it gets its own, larger count. */
    private val idRaceRounds = 600

    private val pin = "4242"

    /**
     * The instant the manager itself sees.
     *
     * It has to be the real clock, not a fixed number: `addIntent` and `addPreset` end with
     * `cleanupExpired(aimiWallClockMs())`, which reads the machine clock and cannot be stubbed. An
     * intent built around a fixed past instant would be expired by the time it was stored.
     */
    private val nowMs = System.currentTimeMillis()

    /** Intents start here and last an hour, so they are live at [nowMs] and expired well after. */
    private val startMs = nowMs - 10 * 60 * 1000L
    private val oneHourMs = 60 * 60 * 1000L

    /** The instant the test passes to `cleanupExpired`, by which the hour is over. */
    private val wellPastMs = startMs + 2 * oneHourMs

    /**
     * A plain in-memory [KeyValueStore]. Hand written rather than mocked: the manager saves on every
     * change, so a recording mock would be the slowest thing in the test.
     */
    private class FakeStore(private val configuredPin: String) : KeyValueStore {

        private val values = ConcurrentHashMap<String, Any>()

        /** When set, [putString] blocks on it. Used to hold a storage write open. */
        @Volatile var blockWritesOn: CountDownLatch? = null

        override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
            val editor = object : KeyValueStore.Editor {
                override fun clear() = values.clear()
                override fun remove(key: String) { values.remove(key) }
                override fun putBoolean(key: String, value: Boolean) { values[key] = value }
                override fun putDouble(key: String, value: Double) { values[key] = value }
                override fun putLong(key: String, value: Long) { values[key] = value }
                override fun putInt(key: String, value: Int) { values[key] = value }
                override fun putString(key: String, value: String) { values[key] = value }
            }
            editor.block()
        }

        override fun getAll(): Map<String, *> = values.toMap()
        override fun clear() = values.clear()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun remove(key: String) { values.remove(key) }

        override fun getString(key: String, defaultValue: String): String {
            if (key == AimiStringKey.RemoteControlPin.key) return configuredPin
            return values[key] as? String ?: defaultValue
        }

        override fun getStringOrNull(key: String, defaultValue: String?): String? =
            values[key] as? String ?: defaultValue

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        override fun getDouble(key: String, defaultValue: Double): Double = values[key] as? Double ?: defaultValue
        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        override fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue

        override fun putString(key: String, value: String) {
            blockWritesOn?.await()
            values[key] = value
        }

        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putDouble(key: String, value: Double) { values[key] = value }
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun putLong(key: String, value: Long) { values[key] = value }

        override fun incInt(key: String) { values[key] = (values[key] as? Int ?: 0) + 1 }
        override fun incLong(key: String) { values[key] = (values[key] as? Long ?: 0L) + 1L }
    }

    /**
     * A logger that writes nothing.
     *
     * `AAPSLoggerTest` prints every line, and `println` takes a lock on `System.out`. That would
     * serialise the two racing threads and hide the very overlap these tests need.
     */
    private object SilentLogger : AAPSLogger {
        override fun debug(message: String) {}
        override fun debug(enable: Boolean, tag: LTag, message: String) {}
        override fun debug(tag: LTag, message: String) {}
        override fun debug(tag: LTag, accessor: () -> String) {}
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun warn(tag: LTag, message: String) {}
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun info(tag: LTag, message: String) {}
        override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(tag: LTag, message: String) {}
        override fun error(tag: LTag, message: String, throwable: Throwable) {}
        override fun error(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(message: String) {}
        override fun error(message: String, throwable: Throwable) {}
        override fun error(format: String, vararg arguments: Any?) {}
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    }

    private fun newManager(store: KeyValueStore, parser: ContextParser = mock()): ContextManager {
        val dateUtil: DateUtil = mock()
        whenever(dateUtil.now()).thenReturn(nowMs)
        val persistenceLayer: PersistenceLayer = mock()
        val llm: AimiContextLlm = mock()
        return ContextManager(
            contextLLMClient = llm,
            contextParser = parser,
            sp = store,
            aapsLogger = SilentLogger,
            persistenceLayer = persistenceLayer,
            dateUtil = dateUtil,
        )
    }

    private fun anActivity() = Activity(
        startTimeMs = startMs,
        durationMs = oneHourMs,
        intensity = Intensity.MEDIUM,
        confidence = 1.0f,
    )

    /**
     * Two adds at the same time must not share an id.
     *
     * This is the hole the `ConcurrentHashMap` really left. `addIntent` is a `suspend fun`, so it
     * could not carry `@Synchronized` the way `addPreset` did, and both of them run
     * `generateId()`, which is `"CTX_<clock>_" + nextId++`. A plain `++` is read-then-write, so in
     * the same millisecond both callers could take the same number, build the same id, and the
     * second write would quietly replace the first intent in the map. The user would add two
     * contexts and get one.
     *
     * Both calls now take [intentsLock] around the id and the insert together, so the two ids
     * cannot be equal.
     */
    @Test
    fun `two adds at the same time never share an id`() {
        val preset = ContextPreset.ALL_PRESETS.first()
        for (round in 0 until idRaceRounds) {
            val parser: ContextParser = mock()
            whenever(parser.parsePreset(any())).thenReturn(anActivity())
            whenever(parser.parse(any())).thenReturn(listOf(anActivity()))

            val manager = newManager(FakeStore(pin), parser)
            val barrier = CyclicBarrier(2)
            val adder = Thread {
                barrier.await()
                runBlocking { manager.addIntent("cardio one hour") }
            }
            val presetAdder = Thread {
                barrier.await()
                manager.addPreset(preset)
            }
            adder.start(); presetAdder.start()
            adder.join(); presetAdder.join()

            assertEquals(2, manager.getAllIntents().size, "round $round: the two adds shared one id")
        }
    }

    /**
     * `removeByType` must report, in total, exactly the intents that were there.
     *
     * Two threads remove the same type at the same time. When the search and the removal were two
     * steps, both could take the same snapshot and both could report the full count, so the two
     * returns added up to twice the number of intents that ever existed. One of those two callers
     * believed it had removed intents that the other had already taken.
     */
    @Test
    fun `removeByType reports each removal once when two threads run it together`() {
        val manager = newManager(FakeStore(pin))
        val barrier = CyclicBarrier(2)

        for (round in 0 until rounds) {
            for (i in 0 until perRound) {
                manager.injectContextFromNS("A${round}_$i", anActivity(), pin)
            }
            assertEquals(perRound, manager.getAllIntents().size, "setup for round $round")

            val total = AtomicInteger(0)
            val threads = (0 until 2).map {
                Thread {
                    barrier.await()
                    total.addAndGet(manager.removeByType(Activity::class))
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(perRound, total.get(), "round $round reported more removals than there were intents")
            assertTrue(manager.getAllIntents().isEmpty(), "round $round left intents behind")
        }
    }

    /**
     * `cleanupExpired` must not drop an intent that another thread has just extended.
     *
     * When the search and the removal were two steps, the search could mark an intent as expired,
     * `extendDuration` could then make it live again and report success, and the removal would still
     * delete it. A user extending a context would see it vanish, with nothing in the log to say why.
     */
    @Test
    fun `cleanupExpired keeps an intent that was extended while it ran`() {
        val manager = newManager(FakeStore(pin))
        val barrier = CyclicBarrier(2)

        for (round in 0 until rounds) {
            val ids = (0 until perRound).map { "E${round}_$it" }
            ids.forEach { manager.injectContextFromNS(it, anActivity(), pin) }

            val extended = ConcurrentHashMap.newKeySet<String>()
            val cleaner = Thread {
                barrier.await()
                manager.cleanupExpired(wellPastMs)
            }
            val extender = Thread {
                barrier.await()
                ids.forEach { id ->
                    if (manager.extendDuration(id, 10.hours)) extended += id
                }
            }
            cleaner.start(); extender.start()
            cleaner.join(); extender.join()

            // Every extend that reported success must have survived: it was live by then.
            extended.forEach { id ->
                assertNotNull(manager.getIntent(id), "round $round lost extended intent $id")
            }
            // Nothing that was not extended may still be there.
            ids.filterNot { it in extended }.forEach { id ->
                assertTrue(manager.getIntent(id) == null, "round $round kept expired intent $id")
            }

            extended.forEach { manager.removeIntent(it) }
        }
    }

    /**
     * The lock must be released before the storage write, so a slow store cannot stall a reader.
     *
     * The loop tick reads this manager from its own thread. If the lock were held while
     * `saveToStorage` wrote, that read would queue behind the write - and if the store ever called
     * back into this class, it would deadlock outright. Here the write is held open and the reader
     * still has to answer.
     */
    @Test
    fun `a blocked storage write does not block a reader`() {
        val store = FakeStore(pin)
        val manager = newManager(store)
        manager.injectContextFromNS("S1", anActivity(), pin)

        val writeReached = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        store.blockWritesOn = releaseWrite

        val writer = Thread {
            writeReached.countDown()
            manager.extendDuration("S1", 1.hours)
        }
        writer.start()
        assertTrue(writeReached.await(5, TimeUnit.SECONDS), "writer never started")

        // Give the writer time to get as far as the blocked store write.
        val readerDone = CountDownLatch(1)
        val seen = AtomicInteger(-1)
        Thread {
            // A short spin so the writer is inside putString, not merely started.
            Thread.sleep(200)
            seen.set(manager.getAllIntents().size)
            readerDone.countDown()
        }.start()

        assertTrue(
            readerDone.await(5, TimeUnit.SECONDS),
            "the reader was still waiting - the lock is being held across the storage write",
        )
        assertEquals(1, seen.get())

        releaseWrite.countDown()
        store.blockWritesOn = null
        writer.join(5_000)
    }
}
