package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineMosaicRepairRequestTest {
    @Test
    fun queuedRepairIsRejectedAfterSessionGenerationChanges() {
        val request = request(timeBucket = "bucket", assetRevision = 1)

        assertTrue(request.belongsToSession(SessionOperationStamp(0L, 0L)))
        assertFalse(request.belongsToSession(SessionOperationStamp(1L, 0L)))
        assertFalse(request.belongsToSession(SessionOperationStamp(0L, 1L)))
    }

    @Test
    fun newestRepairRequestReplacesOlderRequestForSameBucket() {
        val requests = coalesceTimelineMosaicRepairRequests(
            listOf(
                request("bucket", assetRevision = 1),
                request("other", assetRevision = 3),
                request("bucket", assetRevision = 2)
            )
        )

        assertEquals(listOf("bucket", "other"), requests.map { it.timeBucket })
        assertEquals(2, requests.first().assetRevision)
    }

    @Test
    fun lateObsoleteRepairRequestDoesNotEvictCurrentRequestForSameBucket() {
        val requests = coalesceTimelineMosaicRepairRequests(
            listOf(
                request("bucket", assetRevision = 3, globalGeneration = 3L),
                request("bucket", assetRevision = 4, globalGeneration = 2L)
            )
        )

        assertEquals(listOf("bucket"), requests.map { it.timeBucket })
        assertEquals(3, requests.single().assetRevision)
        assertEquals(3L, requests.single().globalGeneration)
    }

    @Test
    fun currentRepairRequestSurvivesPublishCoalescing() {
        val repair = request("bucket", assetRevision = 2)

        val publish = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = listOf(
                PendingMosaicPublish(
                    sessionStamp = repair.sessionStamp,
                    stateUpdates = emptyMap(),
                    geometryUpdates = emptyMap(),
                    stamp = TimelineMosaicPublishStamp(
                        globalGeneration = 1L,
                        bucketGenerations = mapOf("bucket" to 1L)
                    ),
                    repairRequests = listOf(repair)
                )
            ),
            currentGlobalGeneration = 1L,
            currentBucketGenerations = mapOf("bucket" to 1L)
        )

        assertEquals(listOf(repair), assertNotNull(publish).repairRequests)
    }

    private fun request(
        timeBucket: String,
        assetRevision: Int,
        globalGeneration: Long = 1L
    ): TimelineMosaicRepairRequest = TimelineMosaicRepairRequest(
        sessionStamp = SessionOperationStamp(0L, 0L),
        timeBucket = timeBucket,
        groupSize = TimelineGroupSize.MONTH,
        columnCount = 2,
        families = emptySet(),
        geometryRequest = TimelineMosaicGeometryRequest(
            availableWidth = 400f,
            maxRowHeight = 300f,
            spacing = 2f
        ),
        assetRevision = assetRevision,
        globalGeneration = globalGeneration,
        bucketGeneration = 1L,
        sectionsByKey = emptyMap()
    )
}
