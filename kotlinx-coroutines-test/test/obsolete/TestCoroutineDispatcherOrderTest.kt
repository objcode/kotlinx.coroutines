/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.test.obsolete

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import kotlin.test.assertEquals

class TestCoroutineDispatcherOrderTest : TestBase() {

    @Test
    fun testAdvanceTimeBy_progressesOnEachDelay() {
        val dispatcher = TestCoroutineDispatcher()
        val scope = TestCoroutineScope(dispatcher)

        expect(1)
        scope.launch {
            expect(2)
            delay(1_000)
            assertEquals(1_000, dispatcher.currentTime)
            expect(4)
            delay(5_00)
            assertEquals(1_500, dispatcher.currentTime)
            expect(5)
            delay(501)
            assertEquals(2_001, dispatcher.currentTime)
            expect(7)
        }
        scope.runCurrent()
        expect(3)
        assertEquals(0, dispatcher.currentTime)
        dispatcher.advanceTimeBy(2_000)
        expect(6)
        assertEquals(2_000, dispatcher.currentTime)
        dispatcher.advanceTimeBy(2)
        expect(8)
        assertEquals(2_002, dispatcher.currentTime)
        scope.cleanupTestCoroutines()
        finish(9)
    }

    @Test
    fun waitForDispatcherBusy_exitsImmediately() {
        val subject = TestCoroutineDispatcher()
        val scope = TestCoroutineScope(subject)

        scope.launch {

        }

        runBlocking {
            assertRunsFast {
                scope.waitForDispatcherBusy(1)
            }
        }
    }

    @Test(expected = TimeoutCancellationException::class)
    fun waitForDispatcherBusy_timetIfInFuture() {
        val subject = TestCoroutineDispatcher()
        val scope = TestCoroutineScope(subject)

        scope.launch {
            delay(1_000)
        }

        runBlocking {
            assertRunsFast {
                scope.runCurrent()
                scope.waitForDispatcherBusy(1)
            }
        }
    }

    @Test
    fun waitForDispatcherBusy_resumesIfResumedByOtherThreadAsync() {
        val subject = TestCoroutineDispatcher()
        val scope = TestCoroutineScope(subject)
        newFixedThreadPoolContext(1, "other pool").use { otherDispatcher ->
            scope.launch {
                delay(50)
                withContext(otherDispatcher) {
                    delay(1)
                }
            }

            runBlocking {
                scope.advanceTimeBy(50)
                assertRunsFast {
                    scope.waitForDispatcherBusy(3_000)
                }
            }

        }
    }
}