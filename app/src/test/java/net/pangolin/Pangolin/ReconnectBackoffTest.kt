package net.pangolin.Pangolin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun growsExponentiallyCapsAndExhausts() {
        val backoff = ReconnectBackoff(
            initialDelayMs = 1_000L,
            maximumDelayMs = 8_000L,
            maximumRetries = 5,
        )

        assertEquals(1_000L, backoff.nextDelayMs())
        backoff.recordRetryAttempt()
        assertEquals(2_000L, backoff.nextDelayMs())
        backoff.recordRetryAttempt()
        assertEquals(4_000L, backoff.nextDelayMs())
        backoff.recordRetryAttempt()
        assertEquals(8_000L, backoff.nextDelayMs())
        backoff.recordRetryAttempt()
        assertEquals(8_000L, backoff.nextDelayMs())
        backoff.recordRetryAttempt()
        assertNull(backoff.nextDelayMs())
        assertTrue(backoff.isExhausted())
    }

    @Test
    fun waitingWithoutAnAttemptDoesNotConsumeRetryBudget() {
        val backoff = ReconnectBackoff(initialDelayMs = 1_000L, maximumDelayMs = 8_000L)

        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(1_000L, backoff.nextDelayMs())
        assertFalse(backoff.isExhausted())
    }

    @Test
    fun resetStartsTheSequenceAgain() {
        val backoff = ReconnectBackoff(initialDelayMs = 1_000L, maximumDelayMs = 8_000L)
        backoff.recordRetryAttempt()
        backoff.recordRetryAttempt()

        backoff.reset()

        assertEquals(1_000L, backoff.nextDelayMs())
    }
}
