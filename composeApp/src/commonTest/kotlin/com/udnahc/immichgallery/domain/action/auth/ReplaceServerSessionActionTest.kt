package com.udnahc.immichgallery.domain.action.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplaceServerSessionActionTest {
    @Test
    fun normalizedEqualityIsNoClearFastPath() = runTest {
        var mutated = false
        val action = action(
            current = ServerConfiguration("https://immich.example/", "key"),
            mutate = { mutated = true; it() }
        )

        assertTrue(action("https://immich.example", "key").isSuccess)
        assertFalse(mutated)
    }

    @Test
    fun urlOrKeyChangeClearsBeforeReplacing() = runTest {
        val events = mutableListOf<String>()
        var current = ServerConfiguration("https://old.example", "old-key")
        val action = ReplaceServerSessionAction(
            currentConfiguration = { current },
            mutate = { block -> events += "mutation"; block() },
            rotateImageCache = { block ->
                events += "image-stop-clear"
                try {
                    block()
                } finally {
                    events += "image-install"
                }
            },
            clearServerState = { events += "clear" },
            replaceConfiguration = { replacement -> events += "replace"; current = replacement }
        )

        assertTrue(action("https://new.example/", "old-key").isSuccess)
        assertEquals(
            listOf("mutation", "image-stop-clear", "clear", "replace", "image-install"),
            events
        )
        assertEquals(ServerConfiguration("https://new.example", "old-key"), current)

        events.clear()
        assertTrue(action("https://new.example", "new-key").isSuccess)
        assertEquals(
            listOf("mutation", "image-stop-clear", "clear", "replace", "image-install"),
            events
        )
    }

    @Test
    fun clearFailurePreservesOldSettings() = runTest {
        val old = ServerConfiguration("https://old.example", "old-key")
        var current = old
        val action = ReplaceServerSessionAction(
            currentConfiguration = { current },
            mutate = { block -> block() },
            rotateImageCache = { block -> block() },
            clearServerState = { error("clear failed") },
            replaceConfiguration = { current = it }
        )

        assertTrue(action("https://new.example", "new-key").isFailure)
        assertEquals(old, current)
    }

    @Test
    fun cancellationRemainsControlFlow() = runTest {
        val action = action(
            current = ServerConfiguration("https://old.example", "old-key"),
            mutate = { throw CancellationException("cancelled") }
        )

        assertFailsWith<CancellationException> {
            action("https://new.example", "new-key")
        }
    }

    private fun action(
        current: ServerConfiguration,
        mutate: suspend (suspend () -> Unit) -> Unit
    ) = ReplaceServerSessionAction(
        currentConfiguration = { current },
        mutate = mutate,
        rotateImageCache = { block -> block() },
        clearServerState = {},
        replaceConfiguration = {}
    )
}
