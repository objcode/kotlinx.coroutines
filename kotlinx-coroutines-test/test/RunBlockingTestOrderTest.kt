/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.test

import kotlinx.coroutines.*
import org.junit.*
import org.junit.Test
import org.junit.rules.*
import kotlin.test.*

class RunBlockingTestOrderTest : TestBase() {

    @get:Rule
    val timeout = Timeout.seconds(1)

    @Test
    fun testExecutionOrder() = runBlockingTest {
        expect(1)
        launch {
            expect(2)
        }
        runCurrent()
        finish(3)
    }

    @Test
    fun testNestedExecutionOrder() = runBlockingTest {
        expect(1)
        launch {
            expect(2)
            launch {
                expect(3)
            }
        }
        runCurrent()
        finish(4)
    }

    @Test
    fun testComplexExecutionOrder() = runBlockingTest {
        expect(1)
        launch {
            expect(2)
            launch {
                expect(4)
                yield()
                expect(6)
            }
            expect(3)
            yield()
            expect(5)
        }
        runCurrent()
        finish(7)
    }

    @Test
    fun testDelayInteraction() = runBlockingTest {
        expect(1)
        val result = async {
            expect(2)
            delay(1)
            expect(4)
            42
        }
        runCurrent()
        expect(3)
        assertEquals(42, result.await())
        finish(5)
    }

}
