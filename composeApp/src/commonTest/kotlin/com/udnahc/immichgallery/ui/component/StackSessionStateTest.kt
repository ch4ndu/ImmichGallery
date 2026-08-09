package com.udnahc.immichgallery.ui.component

import androidx.compose.ui.input.pointer.PointerEventPass
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetStack
import com.udnahc.immichgallery.domain.model.AssetStackAccessException
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.ui.model.StackUiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackSessionStateTest {
    @Test
    fun unknownPreflightTransitionsThroughCheckingToAvailable() {
        val checking = StackBrowserState().beginPreflight("origin")
        val request = requireNotNull(checking.activeRequest)

        assertIs<StackAvailability.Checking>(checking.availability("origin"))
        val available = checking.complete(
            request,
            Result.success(AssetStackLookup.Available(stack("origin")))
        )

        assertIs<StackAvailability.Available>(available.availability("origin"))
        assertNull(available.activeRequest)
    }

    @Test
    fun successfulAuthoritativeNoStackIsTheOnlyNoStackTransition() {
        val checking = StackBrowserState().beginPreflight("origin")
        val noStack = checking.complete(
            requireNotNull(checking.activeRequest),
            Result.success(AssetStackLookup.NoStack)
        )

        assertIs<StackAvailability.NoStack>(noStack.availability("origin"))
    }

    @Test
    fun permissionAndTransientFailuresRemainRetryableAndNeverBecomeNoStack() {
        listOf(
            AssetStackAccessException.PermissionDenied(IllegalStateException("denied")) to
                StackUiMessage.PermissionDenied,
            AssetStackAccessException.Unavailable(IllegalStateException("missing endpoint")) to
                StackUiMessage.Unavailable,
            AssetStackAccessException.Transient(IllegalStateException("offline")) to
                StackUiMessage.RequestFailed
        ).forEach { (failure, expectedMessage) ->
            val checking = StackBrowserState().beginPreflight("origin")
            val failed = checking.complete(
                requireNotNull(checking.activeRequest),
                Result.failure(failure)
            )

            val availability = assertIs<StackAvailability.Failed>(failed.availability("origin"))
            assertEquals(expectedMessage, availability.message)
            assertIs<StackAvailability.Checking>(failed.retryPreflight("origin").availability("origin"))
        }
    }

    @Test
    fun unsupportedOnlyStackIsFailedRatherThanNoStack() {
        val checking = StackBrowserState().beginPreflight("origin")
        val failed = checking.complete(
            requireNotNull(checking.activeRequest),
            Result.success(AssetStackLookup.NoSupportedMembers)
        )

        val availability = assertIs<StackAvailability.Failed>(failed.availability("origin"))
        assertEquals(StackUiMessage.NoSupportedMembers, availability.message)
    }

    @Test
    fun stalePreflightCannotPublishAfterBasePagerChangesAsset() {
        val checking = StackBrowserState().beginPreflight("origin")
        val request = requireNotNull(checking.activeRequest)
        val moved = checking.settledAssetChanged("other")

        assertNull(moved.activeRequest)
        assertIs<StackAvailability.Unknown>(moved.availability("origin"))
        assertEquals(
            moved,
            moved.complete(request, Result.success(AssetStackLookup.Available(stack("origin"))))
        )
    }

    @Test
    fun availablePreflightIsReusedAndNestedDismissPreservesOrigin() {
        val checking = StackBrowserState().beginPreflight("origin")
        val available = checking.complete(
            requireNotNull(checking.activeRequest),
            Result.success(AssetStackLookup.Available(stack("origin")))
        )
        val opened = available.beginSession("origin")

        val ready = assertIs<StackSession.Ready>(opened.session)
        assertEquals("origin", ready.originAssetId)
        assertNull(opened.activeRequest)

        val dismissed = opened.dismissStackSession()
        assertNull(dismissed.session)
        assertIs<StackAvailability.Available>(dismissed.availability("origin"))
        assertEquals(dismissed, dismissed.dismissStackSession())
    }

    @Test
    fun staleKnownStackBecomesRetryableNoLongerStackedSessionError() {
        val sourceAsset = asset("origin", stackCount = 2)
        val loading = StackBrowserState().beginSession("origin")
        val failed = loading.complete(
            requireNotNull(loading.activeRequest),
            Result.success(AssetStackLookup.NoStack)
        )

        val session = assertIs<StackSession.Failed>(failed.session)
        assertEquals("origin", session.originAssetId)
        assertEquals(StackUiMessage.NoLongerStacked, session.message)
        assertIs<StackSession.Loading>(failed.retrySession().session)

        val dismissed = failed.dismissStackSession()
        assertEquals(
            StackMenuPresentation.Failed(StackUiMessage.NoLongerStacked),
            stackMenuPresentation(sourceAsset.stackCount, dismissed.availability(sourceAsset.id))
        )
    }

    @Test
    fun authoritativeAvailabilityOverridesStalePositiveStackCount() {
        val available = StackAvailability.Available(stack("origin"))

        assertEquals(
            StackMenuPresentation.ViewStack,
            stackMenuPresentation(stackCount = 2, StackAvailability.Unknown)
        )
        assertEquals(
            StackMenuPresentation.Hidden,
            stackMenuPresentation(stackCount = 0, StackAvailability.Unknown)
        )
        assertEquals(
            StackMenuPresentation.ViewStack,
            stackMenuPresentation(stackCount = 0, available)
        )
        assertEquals(
            StackMenuPresentation.ViewStack,
            stackMenuPresentation(stackCount = 2, available)
        )
        assertEquals(
            StackMenuPresentation.Checking,
            stackMenuPresentation(stackCount = 2, StackAvailability.Checking(requestId = 1L))
        )
        assertEquals(
            StackMenuPresentation.Failed(StackUiMessage.PermissionDenied),
            stackMenuPresentation(
                stackCount = 2,
                StackAvailability.Failed(StackUiMessage.PermissionDenied)
            )
        )
        assertEquals(
            StackMenuPresentation.Failed(StackUiMessage.RequestFailed),
            stackMenuPresentation(
                stackCount = 0,
                StackAvailability.Failed(StackUiMessage.RequestFailed)
            )
        )
        assertEquals(
            StackMenuPresentation.Hidden,
            stackMenuPresentation(stackCount = 2, StackAvailability.NoStack)
        )
    }

    @Test
    fun statusPointerBarrierBlocksCoveredViewerWithoutStealingControlInput() {
        assertEquals(PointerEventPass.Final, stackSessionStatusPointerEventPass)
        assertTrue(shouldConsumeStackSessionStatusPointerChange(isConsumed = false))
        assertFalse(shouldConsumeStackSessionStatusPointerChange(isConsumed = true))
    }

    @Test
    fun sparseSourceCanDiscoverStackFromAuthoritativeDetailRead() {
        val sourceAsset = asset("origin", stackCount = 0)
        val checking = StackBrowserState().beginPreflight(sourceAsset.id)
        val available = checking.complete(
            requireNotNull(checking.activeRequest),
            Result.success(AssetStackLookup.Available(stack(sourceAsset.id)))
        )

        assertIs<StackAvailability.Available>(available.availability(sourceAsset.id))
    }

    private fun stack(originId: String): AssetStack = AssetStack.normalized(
        id = "stack-1",
        primaryAssetId = "primary",
        assets = listOf(asset("primary"), asset(originId), asset("child"), asset("child"))
    )

    private fun asset(id: String, stackCount: Int = 0): Asset = Asset(
        id = id,
        type = AssetType.IMAGE,
        fileName = "$id.jpg",
        createdAt = "2026-08-09T00:00:00Z",
        thumbnailUrl = "https://example.test/$id/thumb",
        originalUrl = "https://example.test/$id/original",
        stackCount = stackCount
    )
}
