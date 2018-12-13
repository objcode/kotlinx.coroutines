/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.test

import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.lang.IllegalArgumentException
import kotlin.coroutines.ContinuationInterceptor

class TestAsyncTest {
    @Test
    fun testDelayWithLaunch() = asyncTest {
        val delay = 1000L

        var executed = false
        launch {
            suspendedDelayedAction(delay) {
                executed = true
            }
        }

        advanceTimeBy(delay / 2)
        assertFalse(executed)

        advanceTimeBy(delay / 2)
        assertTrue(executed)
    }

    @Test
    fun testDelayWithAsync() = asyncTest {
        val delay = 1000L

        var executed = false
        async {
            suspendedDelayedAction(delay) {
                executed = true
            }
        }

        advanceTimeBy(delay / 2)
        assertFalse(executed)

        advanceTimeBy(delay / 2)
        assertTrue(executed)
    }

    @Test
    fun testDelayWithRunBlocking() = asyncTest {
        val delay = 1000L

        var executed = false
        runBlocking(coroutineContext) {
            suspendedDelayedAction(delay) {
                executed = true
            }
        }

        assertTrue(executed)
        assertEquals(delay, currentTime())
    }

    private suspend fun suspendedDelayedAction(delay: Long, action: () -> Unit) {
        delay(delay)
        action()
    }

    @Test
    fun testDelayedFunctionWithRunBlocking() = asyncTest {
        val delay = 1000L
        val expectedValue = 16

        val result = runBlocking(coroutineContext) {
            suspendedDelayedFunction(delay) {
                expectedValue
            }
        }

        assertEquals(expectedValue, result)
        assertEquals(delay, currentTime())
    }

    @Test
    fun testDelayedFunctionWithAsync() = asyncTest {
        val delay = 1000L
        val expectedValue = 16

        val deferred = async {
            suspendedDelayedFunction(delay) {
                expectedValue
            }
        }

        advanceTimeBy(delay / 2)
        try {
            deferred.getCompleted()
            fail("The Job should not have been completed yet.")
        } catch (e: Exception) {
            // Success.
        }

        advanceTimeBy(delay / 2)
        assertEquals(expectedValue, deferred.getCompleted())
    }

    private suspend fun <T> CoroutineScope.suspendedDelayedFunction(delay: Long, function: () -> T): T {
        delay(delay / 4)
        return async {
            delay((delay / 4) * 3)
            function()
        }.await()
    }

    @Test
    fun testBlockingFunctionWithRunBlocking() = asyncTest {
        val delay = 1000L
        val expectedValue = 16
        val result = runBlocking(coroutineContext) {
            suspendedBlockingFunction(delay) {
                expectedValue
            }
        }
        assertEquals(expectedValue, result)
        assertEquals(delay, currentTime())
    }

    @Test
    fun testBlockingFunctionWithAsync() = asyncTest {
        val delay = 1000L
        val expectedValue = 16
        var now = 0L
        val deferred = async {
            suspendedBlockingFunction(delay) {
                expectedValue
            }
        }
        now += advanceTimeBy((delay / 4) - 1)
        assertEquals((delay / 4) - 1, now)
        assertEquals(now, currentTime())
        try {
            deferred.getCompleted()
            fail("The Job should not have been completed yet.")
        } catch (e: Exception) {
            // Success.
        }
        now += advanceTimeBy(1)
        assertEquals(delay, currentTime())
        assertEquals(now, currentTime())
        assertEquals(expectedValue, deferred.getCompleted())
    }

    private suspend fun <T> CoroutineScope.suspendedBlockingFunction(delay: Long, function: () -> T): T {
        delay(delay / 4)
        return runBlocking(coroutineContext) {
            delay((delay / 4) * 3)
            function()
        }
    }

    @Test
    fun testTimingOutFunctionWithAsyncAndNoTimeout() = asyncTest {
        val delay = 1000L
        val expectedValue = 67

        val result = async {
            suspendedTimingOutFunction(delay, delay + 1) {
                expectedValue
            }
        }

        runCurrent()
        assertEquals(expectedValue, result.getCompleted())
    }

    @Test
    fun testTimingOutFunctionWithAsyncAndTimeout() = asyncTest {
        val delay = 1000L
        val expectedValue = 67

        val result = async {
            suspendedTimingOutFunction(delay, delay) {
                expectedValue
            }
        }

        runCurrent()
        assertTrue(result.getCompletionExceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun testTimingOutFunctionWithRunBlockingAndTimeout() = asyncTest {
        val delay = 1000L
        val expectedValue = 67

        try {
            runBlocking(coroutineContext) {
                suspendedTimingOutFunction(delay, delay) {
                    expectedValue
                }
            }
            fail("Expected TimeoutCancellationException to be thrown.")
        } catch (e: TimeoutCancellationException) {
            // Success
        } catch (e: Throwable) {
            fail("Expected TimeoutCancellationException to be thrown: $e")
        }
    }

    private suspend fun <T> CoroutineScope.suspendedTimingOutFunction(delay: Long, timeOut: Long, function: () -> T): T {
        return runBlocking(coroutineContext) {
            withTimeout(timeOut) {
                delay(delay / 2)
                val ret = function()
                delay(delay / 2)
                ret
            }
        }
    }

    @Test(expected = IllegalAccessError::class)
    fun testWithTestContextThrowingAnAssertionError() = asyncTest {
        val expectedError = IllegalAccessError("hello")

        val job = launch {
            throw expectedError
        }

        runCurrent()
        // don't rethrow or handle the exception
    }

    @Test(expected = IllegalAccessError::class)
    fun testExceptionHandlingWithLaunch() = asyncTest {
        val expectedError = IllegalAccessError("hello")

        launch {
            throw expectedError
        }

        runCurrent()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testExceptionHandlingWithLaunchingChildCoroutines() = asyncTest {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        val job = launch {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, true)
        }

        advanceTimeBy(delay)
        assertTrue(job.isCancelled)
    }

    @Test
    fun testExceptionHandlingWithAsyncAndDontWaitForException() = asyncTest {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        val result = async {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, false)
        }

        advanceTimeBy(delay)
        assertEquals(expectedError, result.getCompletionExceptionOrNull()?.cause)
    }

    @Test
    fun testExceptionHandlingWithAsyncAndWaitForException() = asyncTest {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        val result = async {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, true)
        }

        advanceTimeBy(delay)

        val e = result.getCompletionExceptionOrNull()
        assertTrue("Expected to be thrown: '$expectedError' but was '$e'", expectedError === e?.cause)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testExceptionHandlingWithRunBlockingAndDontWaitForException() = asyncTest {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        val result = runBlocking(coroutineContext) {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, false)
        }

        advanceTimeBy(delay)

        assertEquals(expectedValue, result)
    }

    @Test
    fun testExceptionHandlingWithRunBlockingAndWaitForException() = asyncTest {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        try {
            runBlocking(coroutineContext) {
                suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, true)
            }
            fail("Expected to be thrown: '$expectedError'")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            assertTrue("Expected to be thrown: '$expectedError' but was '$e'", expectedError === e.cause)
        }
    }

    private suspend fun <T> CoroutineScope.suspendedAsyncWithExceptionAfterDelay(delay: Long, exception: Throwable, value: T, await: Boolean): T {
        val deferred = async {
            delay(delay - 1)
            throw IllegalArgumentException(exception)
        }

        if (await) {
            deferred.await()
        }
        return value
    }

    @Test
    fun testCancellationException() = asyncTest {
        var actual: CancellationException? = null
        val job = launch {
            actual = kotlin.runCatching { delay(1000) }.exceptionOrNull() as? CancellationException
        }

        runCurrent()
        assertNull(actual)

        job.cancel()
        runCurrent()
        assertNotNull(actual)
    }

    @Test()
    fun testCancellationExceptionNotThrownByWithTestContext() = asyncTest {
        val job = launch {
            delay(1000)
        }

        runCurrent()
        job.cancel()
    }

    @Test(expected = UncompletedCoroutinesError::class)
    fun asyncTest_withUnfinishedCoroutines_failTest() {
        val unfinished = CompletableDeferred<Unit>()
        asyncTest {
            launch { unfinished.await() }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun asyncTest_withUnhandledExceptions_failsTest() {
        asyncTest {
            launch {
                throw IllegalArgumentException("IAE")
            }
            runCurrent()
        }
    }

    @Test
    fun scopeExtensionBuilder_passesContext() {
        val scope = TestCoroutineScope()
        scope.asyncTest {
            async {
                delay(5_000)
            }
            advanceTimeToNextDelayed()

            assertSame(scope.coroutineContext[ContinuationInterceptor],
                    coroutineContext[ContinuationInterceptor])
            assertSame(scope.coroutineContext[CoroutineExceptionHandler],
                    coroutineContext[CoroutineExceptionHandler])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun asyncTestBuilder_throwsOnBadDispatcher() {
        asyncTest(newSingleThreadContext("name")) {

        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun asyncTestBuilder_throwsOnBadHandler() {
        asyncTest(CoroutineExceptionHandler { _, _ -> Unit} ) {
        }
    }

    @Test
    fun whenUsingAnotherDispatcher_itDoesNotProvideTimeSkipping() {
        val other = newSingleThreadContext("another dispatcher")
        val scope = TestCoroutineScope()
        var calls = 0
        scope.asyncTest {
            val dispatcher = coroutineContext[ContinuationInterceptor]
            val deferred = async {
                calls++
                withContext(other) {
                    assertNotSame(dispatcher, coroutineContext[ContinuationInterceptor])
                    calls++
                    3
                }
            }

            this.runBlockingTest {
                // the only way to make the thread switch is to use runBlocking and await()
                assertEquals(3, deferred.await())
            }

            assertEquals(2, calls)
            other.close()
        }
    }

    @Test
    fun withContext_usingSharedInjectedDispatcher_runsFasts() {
        val dispatcher = TestCoroutineDispatcher()
        val scope = TestCoroutineScope(dispatcher)

        scope.asyncTest {
            val deferred = async {
                // share the same dispatcher (via e.g. injection or service locator)
                withContext(dispatcher) {
                    assertRunsFast {
                        delay(SLOW)
                    }
                    3
                }
            }
            advanceUntilIdle()
            assertEquals(3, deferred.getCompleted())
        }
    }
}
