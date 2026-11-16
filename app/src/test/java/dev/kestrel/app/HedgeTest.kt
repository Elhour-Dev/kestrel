package dev.kestrel.app

import dev.kestrel.app.data.streams.Streams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline checks for the link-source hedge (preferred source first, the other after a delay). */
class HedgeTest {
    private val hedge = 100L

    @Test fun fastFirstWins() = runBlocking {
        var secondRan = false
        val r = Streams.hedged({ delay(10); "a" }, { secondRan = true; "b" }, hedge)
        assertEquals("a", r); assertTrue(!secondRan)
    }

    @Test fun slowFirstLosesToSecond() = runBlocking {
        assertEquals("b", Streams.hedged({ delay(2_000); "a" }, { delay(10); "b" }, hedge))
    }

    @Test fun slowFirstStillWinsWhenSecondIsSlower() = runBlocking {
        assertEquals("a", Streams.hedged({ delay(300); "a" }, { delay(2_000); "b" }, hedge))
    }

    @Test fun firstFailsQuicklyThenSecond() = runBlocking {
        assertEquals("b", Streams.hedged<String>({ null }, { delay(10); "b" }, hedge))
    }

    @Test fun firstFailsAfterHedgeThenSecondAnswers() = runBlocking {
        assertEquals("b", Streams.hedged<String>({ delay(200); null }, { delay(500); "b" }, hedge))
    }

    @Test fun secondFailsFirstAnswersLater() = runBlocking {
        assertEquals("a", Streams.hedged<String>({ delay(500); "a" }, { delay(10); null }, hedge))
    }

    @Test fun bothFail() = runBlocking {
        assertNull(Streams.hedged<String>({ delay(200); null }, { delay(10); null }, hedge))
    }

    @Test fun loserIsCancelled() = runBlocking {
        val cancelled = CompletableDeferred<Boolean>()
        val r = Streams.hedged({
            try { delay(5_000); "a" } catch (e: kotlinx.coroutines.CancellationException) { cancelled.complete(true); throw e }
        }, { delay(10); "b" }, hedge)
        assertEquals("b", r)
        assertTrue(kotlinx.coroutines.withTimeout(1_000) { cancelled.await() })
    }

    @Test fun finalErrorEndsHedgeAndStopsOther() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val e = runCatching {
            Streams.hedged<String>({ try { delay(5_000); "a" } finally { stopped.complete(Unit) } }, { delay(10); throw IllegalStateException("removed") }, hedge)
        }.exceptionOrNull()
        assertEquals("removed", e?.message)
        kotlinx.coroutines.withTimeout(1_000) { stopped.await() }
    }

    @Test fun callerCancelStopsBoth() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val job = launch {
            Streams.hedged({ try { delay(5_000); "a" } finally { stopped.complete(Unit) } }, { delay(5_000); "b" }, hedge)
        }
        delay(300); job.cancel()
        kotlinx.coroutines.withTimeout(1_000) { stopped.await() }
    }
}
