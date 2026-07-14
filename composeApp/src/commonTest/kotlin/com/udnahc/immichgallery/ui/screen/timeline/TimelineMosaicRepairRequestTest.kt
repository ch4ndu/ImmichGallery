package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TimelineMosaicRepairRequestTest {
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
    fun currentRepairRequestSurvivesPublishCoalescing() {
        val repair = request("bucket", assetRevision = 2)

        val publish = TimelineMosaicPublishBuffer().coalesceCurrent(
            pending = listOf(
                PendingMosaicPublish(
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
        assetRevision: Int
    ): TimelineMosaicRepairRequest = TimelineMosaicRepairRequest(
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
        globalGeneration = 1L,
        bucketGeneration = 1L,
        sectionsByKey = emptyMap()
    )
}
