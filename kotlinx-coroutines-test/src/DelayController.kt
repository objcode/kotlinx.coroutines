package kotlinx.coroutines.test

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ConflatedBroadcastChannel

/**
 * Control the virtual clock time of a [CoroutineDispatcher].
 *
 * Testing libraries may expose this interface to tests instead of [TestCoroutineDispatcher].
 */
@ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
public interface DelayController {
    /**
     * Returns the current virtual clock-time as it is known to this Dispatcher.
     *
     * @return The virtual clock-time
     */
    @ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
    public val currentTime: Long

    /**
     * Moves the Dispatcher's virtual clock forward by a specified amount of time.
     *
     * The virtual clock time will advance once for each delay resumed until the next delay exceeds the requested
     * `delayTimeMills`. In the following test, the virtual time will progress by 2_000 then 1 to resume three different
     * calls to delay.
     *
     * ```
     * @Test
     * fun advanceTimeTest() = runBlockingTest {
     *     foo()
     *     advanceTimeBy(2_000)  // advanceTimeBy(2_000) will progress through the first two delays
     *     // virtual time is 2_000, next resume is at 2_001
     *     advanceTimeBy(2)      // progress through the last delay of 501 (note 500ms were already advanced)
     *     // virtual time is 2_0002
     * }
     *
     * fun CoroutineScope.foo() {
     *     launch {
     *         delay(1_000)    // advanceTimeBy(2_000) will progress through this delay (resume @ virtual time 1_000)
     *         // virtual time is 1_000
     *         delay(500)      // advanceTimeBy(2_000) will progress through this delay (resume @ virtual time 1_500)
     *         // virtual time is 1_500
     *         delay(501)      // advanceTimeBy(2_000) will not progress through this delay (resume @ virtual time 2_001)
     *         // virtual time is 2_001
     *     }
     * }
     * ```
     *
     * @param delayTimeMillis The amount of time to move the CoroutineContext's clock forward.
     * @return The amount of delay-time that this Dispatcher's clock has been forwarded.
     */
    @ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
    public fun advanceTimeBy(delayTimeMillis: Long): Long

    /**
     * Immediately execute all pending tasks and advance the virtual clock-time to the last delay.
     *
     * If new tasks are scheduled while advancing virtual time, they will be executed before `advanceUntilIdle`
     * returns.
     *
     * @return the amount of delay-time that this Dispatcher's clock has been forwarded in milliseconds.
     */
    @ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
    public fun advanceUntilIdle(): Long

    /**
     * Run any tasks that are pending at or before the current virtual clock-time.
     *
     * Calling this function will never advance the clock.
     */
    @ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
    public fun runCurrent()

    /**
     * Call after test code completes to ensure that the dispatcher is properly cleaned up.
     *
     * @throws UncompletedCoroutinesError if any pending tasks are active, however it will not throw for suspended
     * coroutines.
     */
    @ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
    @Throws(UncompletedCoroutinesError::class)
    public fun cleanupTestCoroutines()

    /**
     * Suspends until at least one task may be executed by calling [runCurrent].
     *
     * If this method returns normally, there must be at least one task that can be executed by calling [runCurrent]. It
     * will return immediately if there is already a task in the queue for the current time.
     *
     * This is useful when another dispatcher is currently processing a coroutine and is expected to dispatch the result
     * to this dispatcher. This most commonly happens due to calls to [withContext] or [Deferred.await].
     *
     * Note: You should not need to call this function from inside [runBlockingTest] as it calls it implicitly, but it
     *       is required for thread coordination in in tests that don't use `runBlockingTest` and interact with multiple
     *       threads.
     *
     * ```
     * val otherDispatcher = // single threaded dispatcher
     *
     * suspend fun switchingThreads() {
     *     withContext(otherDispatcher) {
     *         // this is not executing on TestCoroutineDispatcher
     *         database.query()
     *     }
     *     println("run me after waiting for withContext")
     * }
     *
     * @Test whenSwitchingThreads_resultsBecomeAvailable() {
     *      val scope = TestCoroutineScope()
     *
     *      val job = scope.launch {
     *          switchingThreads()
     *      }
     *
     *      scope.runCurrent() // run to withContext, which will dispatch on otherDispatcher
     *      runBlocking {
     *          // wait for otherDispatcher to return control of the coroutine to TestCoroutineDispatcher
     *          scope.waitForDispatcherBusy(2_000) // throws timeout exception if withContext doesn't finish in 2_000ms
     *      }
     *      scope.runCurrent() // run the dispatched task (everything after withContext)
     *      // job.isCompleted == true
     * }
     * ```
     *
     * Whenever possible, it is preferred to inject [TestCoroutineDispatcher] to the [withContext] call instead of
     * calling `waitForDispatcherBusy` as it creates a single threaded test and avoids thread synchronization issues.
     *
     * Calling this method will never change the virtual-time.
     *
     * @param timeoutMills how long to wait for a task to be dispatched to this dispatcher.
     */
    suspend fun waitForDispatcherBusy(timeoutMills: Long)
}

/**
 * Thrown when a test has completed and there are tasks that are not completed or cancelled.
 */
// todo: maybe convert into non-public class in 1.3.0 (need use-cases for a public exception type)
@ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
public class UncompletedCoroutinesError(message: String, cause: Throwable? = null): AssertionError(message, cause)
