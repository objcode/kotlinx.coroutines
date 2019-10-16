/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.test

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.internal.ThreadSafeHeap
import kotlinx.coroutines.internal.ThreadSafeHeapNode
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

/**
 * [CoroutineDispatcher] that offers lazy, determistic, execution of coroutines in tests
 * and implements [DelayController] to control its virtual clock.
 *
 * Any coroutines started via [launch] or [async] will not execute until a call to [DelayController.runCurrent] or the
 * virtual clock-time has been advanced via one of the methods on [DelayController].
 *
 * [TestCoroutineDispatcher] does not hold a thread, so if a coroutine switches to another thread via [withContext] or
 * similar, this dispatcher will not automatically wait for the other thread to pass control back. Tests can wait for
 * the other dispatcher by calling [DelayController.waitForDispatcherBusy], then call [DelayController.runCurrent] to
 * run the dispatched task from the test thread. Tests that use [runBlockingTest] do not need to call
 * [DelayController.waitForDispatcherBusy].
 *
 * @see DelayController
 */
@ExperimentalCoroutinesApi // Since 1.2.1, tentatively till 1.3.0
public class TestCoroutineDispatcher: CoroutineDispatcher(), Delay, DelayController {

    // The ordered queue for the runnable tasks.
    private val queue = ThreadSafeHeap<TimedRunnable>()

    // The per-scheduler global order counter.
    private val _counter = atomic(0L)

    // Storing time in nanoseconds internally.
    private val _time = atomic(0L)

    private val waitLock = Channel<Unit>(capacity = 1)

    /** @suppress */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        post(block)
    }

    /** @suppress */
    @InternalCoroutinesApi
    override fun dispatchYield(context: CoroutineContext, block: Runnable) {
        post(block)
    }

    /** @suppress */
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        postDelayed(CancellableContinuationRunnable(continuation) { resumeUndispatched(Unit) }, timeMillis)
    }

    /** @suppress */
    override fun invokeOnTimeout(timeMillis: Long, block: Runnable): DisposableHandle {
        val node = postDelayed(block, timeMillis)
        return object : DisposableHandle {
            override fun dispose() {
                queue.remove(node)
            }
        }
    }

    /** @suppress */
    override fun toString(): String {
        return "TestCoroutineDispatcher[currentTime=${currentTime}ms, queued=${queue.size}]"
    }

    private fun post(block: Runnable) {
        queue.addLast(TimedRunnable(block, _counter.getAndIncrement()))
        unpark()
    }

    private fun postDelayed(block: Runnable, delayTime: Long): TimedRunnable {
        return TimedRunnable(block, _counter.getAndIncrement(), safePlus(currentTime, delayTime))
                .also {
                    queue.addLast(it)
                    unpark()
                }
    }

    private fun safePlus(currentTime: Long, delayTime: Long): Long {
        check(delayTime >= 0)
        val result = currentTime + delayTime
        if (result < currentTime) return Long.MAX_VALUE // clam on overflow
        return result
    }

    private fun doActionsUntil(targetTime: Long) {
        while (true) {
            val current = queue.removeFirstIf { it.time <= targetTime } ?: break
            // If the scheduled time is 0 (immediate) use current virtual time
            if (current.time != 0L) _time.value = current.time
            current.run()
        }
    }

    /** @suppress */
    override val currentTime get() = _time.value

    /** @suppress */
    override fun advanceTimeBy(delayTimeMillis: Long): Long {
        val oldTime = currentTime
        advanceUntilTime(oldTime + delayTimeMillis)
        return currentTime - oldTime
    }

    /**
     * Moves the CoroutineContext's clock-time to a particular moment in time.
     *
     * @param targetTime The point in time to which to move the CoroutineContext's clock (milliseconds).
     */
    private fun advanceUntilTime(targetTime: Long) {
        doActionsUntil(targetTime)
        _time.update { currentValue -> max(currentValue, targetTime) }
    }

    /** @suppress */
    override fun advanceUntilIdle(): Long {
        val oldTime = currentTime
        while(!queue.isEmpty) {
            runCurrent()
            val next = queue.peek() ?: break
            advanceUntilTime(next.time)
        }

        return currentTime - oldTime
    }

    /** @suppress */
    override fun runCurrent() {
        doActionsUntil(currentTime)
    }

    /** @suppress */
    override fun cleanupTestCoroutines() {
        unpark()
        // process any pending cancellations or completions, but don't advance time
        doActionsUntil(currentTime)

        // run through all pending tasks, ignore any submitted coroutines that are not active
        val pendingTasks = mutableListOf<TimedRunnable>()
        while (true) {
            pendingTasks += queue.removeFirstOrNull() ?: break
        }
        val activeDelays = pendingTasks
            .mapNotNull { it.runnable as? CancellableContinuationRunnable<*> }
            .filter { it.continuation.isActive }

        val activeTimeouts = pendingTasks.filter { it.runnable !is CancellableContinuationRunnable<*> }
        if (activeDelays.isNotEmpty() || activeTimeouts.isNotEmpty()) {
            throw UncompletedCoroutinesError(
                "Unfinished coroutines during teardown. Ensure all coroutines are" +
                    " completed or cancelled by your test."
            )
        }
    }

    override suspend fun waitForDispatcherBusy(timeoutMills: Long) {
        withTimeout(timeoutMills) {
            while (true) {
                val nextTime = queue.peek()?.time
                if (nextTime != null && nextTime <= currentTime) {
                    break
                }
                waitLock.receive()
            }
        }
    }

    private fun unpark() {
        waitLock.offer(Unit)
    }
}

/**
 * This class exists to allow cleanup code to avoid throwing for cancelled continuations scheduled
 * in the future.
 */
private class CancellableContinuationRunnable<T>(
    @JvmField val continuation: CancellableContinuation<T>,
    private val block: CancellableContinuation<T>.() -> Unit
) : Runnable {
    override fun run() = continuation.block()
}

/**
 * A Runnable for our event loop that represents a task to perform at a time.
 */
private class TimedRunnable(
    @JvmField val runnable: Runnable,
    private val count: Long = 0,
    @JvmField val time: Long = 0
) : Comparable<TimedRunnable>, Runnable by runnable, ThreadSafeHeapNode {
    override var heap: ThreadSafeHeap<*>? = null
    override var index: Int = 0

    override fun compareTo(other: TimedRunnable) = if (time == other.time) {
        count.compareTo(other.count)
    } else {
        time.compareTo(other.time)
    }

    override fun toString() = "TimedRunnable(time=$time, run=$runnable)"
}
