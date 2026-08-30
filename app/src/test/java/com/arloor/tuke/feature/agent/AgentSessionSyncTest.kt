package com.arloor.tuke.feature.agent

import com.arloor.tuke.core.agent.RunEventsResponse
import com.arloor.tuke.core.agent.SessionDetailResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionSyncTest {
    @Test
    fun immediateFailureKeepsRetryingUntilSessionSettles() = runTest {
        var fetchCount = 0
        val failures = mutableListOf<Throwable>()
        val runningStates = mutableListOf<Boolean>()

        val job = backgroundScope.launch {
            runEventSyncLoop(
                pollImmediately = true,
                intervalMs = 1_500,
                shouldContinue = { true },
                fetch = {
                    fetchCount++
                    when (fetchCount) {
                        1 -> error("temporary outage")
                        2 -> RunEventsResponse(running = true)
                        else -> RunEventsResponse(running = false)
                    }
                },
                onFailure = { failures += it },
                onBatch = { runningStates += it.running },
            )
        }

        runCurrent()
        assertEquals(1, fetchCount)
        assertEquals(1, failures.size)

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, fetchCount)
        assertEquals(listOf(true), runningStates)

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(3, fetchCount)
        assertEquals(listOf(true, false), runningStates)
        assertEquals(true, job.isCompleted)
    }

    @Test
    fun normalPollingWaitsBeforeFirstAttempt() = runTest {
        var fetchCount = 0

        val job = backgroundScope.launch {
            runEventSyncLoop(
                pollImmediately = false,
                intervalMs = 1_500,
                shouldContinue = { true },
                fetch = {
                    fetchCount++
                    RunEventsResponse(running = false)
                },
                onFailure = {},
                onBatch = {},
            )
        }

        runCurrent()
        assertEquals(0, fetchCount)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, fetchCount)
        assertEquals(true, job.isCompleted)
    }

    @Test
    fun sessionReconciliationDetectsNewRunAndResetsCursor() = runTest {
        val requestedCursors = mutableListOf<Long>()
        val batchRunningStates = mutableListOf<Boolean>()
        val reconciledRunningStates = mutableListOf<Boolean>()
        var eventFetchCount = 0
        var sessionFetchCount = 0

        runSessionRecoveryLoop(
            pollImmediately = true,
            intervalMs = 1,
            shouldContinue = { true },
            fetchEvents = { cursor ->
                requestedCursors += cursor
                eventFetchCount++
                when (eventFetchCount) {
                    1 -> RunEventsResponse(nextCursor = 7, running = true)
                    2 -> RunEventsResponse(nextCursor = 8, running = false)
                    else -> RunEventsResponse(nextCursor = 1, running = false)
                }
            },
            fetchSession = {
                sessionFetchCount++
                SessionDetailResponse(running = sessionFetchCount == 1)
            },
            onFailure = { throw AssertionError("unexpected failure", it) },
            onBatch = { batchRunningStates += it.running },
            onSessionDetail = { reconciledRunningStates += it.running },
        )

        assertEquals(listOf(0L, 7L, 0L), requestedCursors)
        assertEquals(listOf(true, false, false), batchRunningStates)
        assertEquals(listOf(true, false), reconciledRunningStates)
        assertEquals(2, sessionFetchCount)
    }
}
