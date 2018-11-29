/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import kotlinx.coroutines.*
import java.util.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

internal actual fun <E : Throwable> recoverStackTrace(exception: E): E {
    if (recoveryDisabled(exception)) {
        return exception
    }

    val copy = tryCopyException(exception) ?: return exception
    return copy.sanitizeStackTrace()
}

private fun <E : Throwable> E.sanitizeStackTrace(): E {
    val stackTrace = stackTrace
    val size = stackTrace.size

    val lastIntrinsic = stackTrace.indexOfFirst { "kotlinx.coroutines.internal.ExceptionsKt" == it.className }
    val startIndex = lastIntrinsic + 1
    val trace = Array(size - lastIntrinsic) {
        if (it == 0) {
            artificialFrame("Current coroutine stacktrace")
        } else {
            stackTrace[startIndex + it - 1]
        }
    }

    setStackTrace(trace)
    return this
}

internal actual fun <E : Throwable> recoverStackTrace(exception: E, continuation: Continuation<*>): E {
    if (recoveryDisabled(exception) || continuation !is CoroutineStackFrame) {
        return exception
    }

    return recoverFromStackFrame(exception, continuation)
}

private fun <E : Throwable> recoverFromStackFrame(exception: E, continuation: CoroutineStackFrame): E {
    val newException = tryCopyException(exception) ?: return exception
    val stacktrace = createStackTrace(continuation)
    if (stacktrace.isEmpty()) return exception
    val copied = meaningfulActualStackTrace(exception)
    stacktrace.add(0, artificialFrame("Current coroutine stacktrace"))
    newException.stackTrace = (copied + stacktrace).toTypedArray() // TODO optimizable
    return newException
}

/*
 * Returns slice of the original stacktrace from the original exception.
 * E.g. for
 * at kotlinx.coroutines.PlaygroundKt.foo(PlaygroundKt.kt:14)
 * at kotlinx.coroutines.PlaygroundKt$foo$1.invokeSuspend(PlaygroundKt.kt)
 * at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:32)
 * at kotlinx.coroutines.ResumeModeKt.resumeMode(ResumeMode.kt:67)
 * at kotlinx.coroutines.DispatchedKt.resume(Dispatched.kt:277)
 * at kotlinx.coroutines.DispatchedKt.dispatch(Dispatched.kt:266)
 *
 * first two elements will be returned.
 */
private fun <E : Throwable> meaningfulActualStackTrace(exception: E): List<StackTraceElement> {
    val stackTrace = exception.stackTrace
    val index = stackTrace.indexOfFirst { "kotlin.coroutines.jvm.internal.BaseContinuationImpl" == it.className }
    if (index == -1) return emptyList()
    return stackTrace.slice(0 until index)
}


@Suppress("NOTHING_TO_INLINE")
internal actual suspend inline fun recoverAndThrow(exception: Throwable): Nothing {
    if (recoveryDisabled(exception)) throw exception
    suspendCoroutineUninterceptedOrReturn<Nothing> {
        if (it !is CoroutineStackFrame) throw exception
        throw recoverFromStackFrame(exception, it)
    }
}

internal actual fun <E : Throwable> unwrap(exception: E): E {
    if (recoveryDisabled(exception)) {
        return exception
    }

    val cause = exception.cause
    // Fast-path to avoid array cloning
    if (cause == null || cause.javaClass != exception.javaClass) {
        return exception
    }

    if (exception.stackTrace.any { it.isArtificial() }) {
        @Suppress("UNCHECKED_CAST")
        return exception.cause as? E ?: exception
    } else {
        return exception
    }
}

private fun <E : Throwable> recoveryDisabled(exception: E) =
    !RECOVER_STACKTRACE || !DEBUG || exception is CancellationException || exception is NonRecoverableThrowable

private fun createStackTrace(continuation: CoroutineStackFrame): ArrayList<StackTraceElement> {
    val stack = ArrayList<StackTraceElement>()
    continuation.getStackTraceElement()?.let { stack.add(sanitize(it)) }

    var last = continuation
    while (true) {
        last = (last as? CoroutineStackFrame)?.callerFrame ?: break
        last.getStackTraceElement()?.let { stack.add(sanitize(it)) }
    }
    return stack
}

internal fun sanitize(element: StackTraceElement): StackTraceElement {
    if (!element.className.contains('/')) {
        return element
    }
    // KT-28237: STE generated with debug metadata contains '/' as separators in FQN, while Java contains dots
    return StackTraceElement(element.className.replace('/', '.'), element.methodName, element.fileName, element.lineNumber)
}
internal fun artificialFrame(message: String) = java.lang.StackTraceElement("\b\b\b($message", "\b", "\b", -1)
internal fun StackTraceElement.isArtificial() = className.startsWith("\b\b\b")

@Suppress("ACTUAL_WITHOUT_EXPECT")
actual typealias CoroutineStackFrame = kotlin.coroutines.jvm.internal.CoroutineStackFrame

actual typealias StackTraceElement = java.lang.StackTraceElement
