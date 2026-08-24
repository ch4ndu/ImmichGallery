package com.udnahc.immichgallery.ui.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigationTest {
    @Test
    fun currentPersonDoesNotPushAnIdenticalRoute() {
        assertFalse(shouldNavigateToPersonDetail("person-a", "person-a"))
        assertTrue(shouldNavigateToPersonDetail("person-a", "person-b"))
    }
}
