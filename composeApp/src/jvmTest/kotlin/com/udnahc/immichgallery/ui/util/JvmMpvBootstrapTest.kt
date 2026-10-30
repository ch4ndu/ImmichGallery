package com.udnahc.immichgallery.ui.util

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmMpvBootstrapTest {
    @Test
    fun transientPreparationFailureWaitsForExplicitRetryAndThenReusesSuccess() {
        val attempts = mutableListOf<Boolean>()
        val preparation = JvmMpvPreparation { retrying ->
            attempts += retrying
            if (attempts.size == 1) throw IOException("interrupted extraction")
        }

        assertEquals(JvmMpvAvailability.RetryableFailure, preparation.initialize())
        repeat(3) { assertEquals(JvmMpvAvailability.RetryableFailure, preparation.initialize()) }
        assertEquals(listOf(false), attempts)

        assertEquals(JvmMpvAvailability.Ready, preparation.initialize(retryPreparation = true))
        repeat(3) {
            assertEquals(JvmMpvAvailability.Ready, preparation.initialize())
            assertEquals(JvmMpvAvailability.Ready, preparation.initialize(retryPreparation = true))
        }
        assertEquals(listOf(false, true), attempts)
    }

    @Test
    fun repeatedTransientFailureRequiresAnotherExplicitRetry() {
        val attempts = mutableListOf<Boolean>()
        val preparation = JvmMpvPreparation { retrying ->
            attempts += retrying
            if (attempts.size <= 2) throw IOException("interrupted extraction")
        }

        assertEquals(JvmMpvAvailability.RetryableFailure, preparation.initialize())
        assertEquals(JvmMpvAvailability.RetryableFailure, preparation.initialize(retryPreparation = true))
        assertEquals(JvmMpvAvailability.RetryableFailure, preparation.initialize())
        assertEquals(listOf(false, true), attempts)
        assertEquals(JvmMpvAvailability.Ready, preparation.initialize(retryPreparation = true))
        assertEquals(listOf(false, true, true), attempts)
    }

    @Test
    fun successfulPreparationIsNeverRepeated() {
        var attempts = 0
        val preparation = JvmMpvPreparation { attempts++ }

        assertEquals(JvmMpvAvailability.Ready, preparation.initialize())
        assertEquals(JvmMpvAvailability.Ready, preparation.initialize())
        assertEquals(JvmMpvAvailability.Ready, preparation.initialize(retryPreparation = true))
        assertEquals(1, attempts)
    }

    @Test
    fun missingInvalidAndIncompatibleRuntimesStayUnavailableAfterExplicitRetry() {
        val failures = listOf(
            IllegalStateException("missing classpath runtime"),
            IllegalArgumentException("missing runtime wrapper"),
            SecurityException("native loading denied"),
            UnsatisfiedLinkError("incompatible native runtime"),
            ExceptionInInitializerError("native loader initialization failed"),
        )
        for (failure in failures) {
            var attempts = 0
            val preparation = JvmMpvPreparation { attempts++; throw failure }
            assertEquals(JvmMpvAvailability.Unavailable, preparation.initialize())
            assertEquals(JvmMpvAvailability.Unavailable, preparation.initialize())
            assertEquals(JvmMpvAvailability.Unavailable, preparation.initialize(retryPreparation = true))
            assertEquals(1, attempts, failure.javaClass.simpleName)
        }
    }
}
