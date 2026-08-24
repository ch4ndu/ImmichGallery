package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class KenBurnsImageTest {
    @Test
    fun interpolateKenBurnsTransformUsesClampedProgress() {
        val preset = KenBurnsPreset(
            startScale = 1f,
            endScale = 1.5f,
            startPanX = -0.1f,
            startPanY = 0.2f,
            endPanX = 0.3f,
            endPanY = -0.2f
        )

        assertTransformEquals(
            expected = KenBurnsTransform(scale = 1f, translationX = -20f, translationY = 20f),
            actual = interpolateKenBurnsTransform(
                preset = preset,
                widthPx = 200f,
                heightPx = 100f,
                progress = -1f
            )
        )
        assertTransformEquals(
            expected = KenBurnsTransform(scale = 1.5f, translationX = 60f, translationY = -20f),
            actual = interpolateKenBurnsTransform(
                preset = preset,
                widthPx = 200f,
                heightPx = 100f,
                progress = 2f
            )
        )
    }

    @Test
    fun interpolateKenBurnsTransformLinearlyBlendsScaleAndPan() {
        val preset = KenBurnsPreset(
            startScale = 1f,
            endScale = 1.4f,
            startPanX = -0.04f,
            startPanY = 0f,
            endPanX = 0.04f,
            endPanY = 0.08f
        )

        assertTransformEquals(
            expected = KenBurnsTransform(scale = 1.2f, translationX = 0f, translationY = 16f),
            actual = interpolateKenBurnsTransform(
                preset = preset,
                widthPx = 300f,
                heightPx = 400f,
                progress = 0.5f
            )
        )
    }

    private fun assertTransformEquals(
        expected: KenBurnsTransform,
        actual: KenBurnsTransform
    ) {
        assertEquals(expected.scale, actual.scale, absoluteTolerance = 0.0001f)
        assertEquals(expected.translationX, actual.translationX, absoluteTolerance = 0.0001f)
        assertEquals(expected.translationY, actual.translationY, absoluteTolerance = 0.0001f)
    }
}
