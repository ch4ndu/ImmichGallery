package com.udnahc.immichgallery

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoroutineResultsTest {
    @Test
    fun cancellationIsRethrown() {
        assertFailsWith<CancellationException> {
            runSuspendCatching<Unit> { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun ordinaryFailureBecomesResultFailure() {
        assertTrue(runSuspendCatching<Unit> { error("failed") }.isFailure)
    }
}
