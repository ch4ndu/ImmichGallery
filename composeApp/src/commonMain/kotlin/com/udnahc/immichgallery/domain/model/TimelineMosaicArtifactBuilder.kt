package com.udnahc.immichgallery.domain.model

class TimelineMosaicArtifactBuilder {
    fun build(
        snapshot: TimelineBucketSnapshot,
        groupSize: TimelineGroupSize,
        groupMode: String,
        columnCount: Int,
        familiesKey: String,
        geometryRequest: TimelineMosaicGeometryRequest?,
        sections: List<TimelineMosaicSection>,
        readySections: List<SectionReady>,
        cacheDisplayResults: Boolean,
        updatedAt: Long
    ): TimelineMosaicBucketArtifacts? {
        val request = geometryRequest ?: return null
        if (mosaicLayoutSpecForColumnCount(request.availableWidth, columnCount) == null) return null
        if (sections.size != readySections.size) return null

        val fingerprint = orderedTimelineAssetsFingerprint(snapshot.assets)
        val assignments = mutableListOf<TimelineMosaicAssignmentArtifact>()
        val displayCache = mutableListOf<TimelineMosaicDisplayArtifact>()
        val sectionGeometry = mutableListOf<TimelineMosaicSectionGeometryArtifact>()
        val geometries = mutableListOf<SectionGeometry>()

        sections.zip(readySections).forEach { (section, ready) ->
            val geometry = ready.geometry
            if (!mosaicGeometryRangesCoverSourceRange(geometry.ranges, section.assets.size)) return null
            if (section.assets.isNotEmpty() && geometry.placeholderHeight <= 0f) return null

            assignments.add(
                TimelineMosaicAssignmentArtifact(
                    timeBucket = snapshot.timeBucket,
                    groupMode = groupMode,
                    sectionKey = section.sectionKey,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    assetFingerprint = fingerprint,
                    assignments = ready.assignments,
                    updatedAt = updatedAt
                )
            )
            if (cacheDisplayResults) {
                val records = resolvedSectionDisplayRecordsOrEmpty(
                    displayItems = ready.displayItems,
                    assets = section.assets
                )
                if (records.isEmpty() && section.assets.isNotEmpty()) return null
                displayCache.add(
                    TimelineMosaicDisplayArtifact(
                        timeBucket = snapshot.timeBucket,
                        groupMode = groupMode,
                        sectionKey = section.sectionKey,
                        columnCount = columnCount,
                        familiesKey = familiesKey,
                        assetFingerprint = fingerprint,
                        geometryRequest = request,
                        displayRecords = records,
                        displayItemCount = records.size,
                        placeholderHeight = geometry.placeholderHeight,
                        updatedAt = updatedAt
                    )
                )
            }
            sectionGeometry.add(
                TimelineMosaicSectionGeometryArtifact(
                    timeBucket = snapshot.timeBucket,
                    groupMode = groupMode,
                    sectionKey = section.sectionKey,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    assetFingerprint = fingerprint,
                    geometryRequest = request,
                    placeholderHeight = geometry.placeholderHeight,
                    displayItemCount = geometry.displayItemCount,
                    ranges = geometry.ranges,
                    updatedAt = updatedAt
                )
            )
            geometries.add(geometry)
        }

        val bucketGeometry = TimelineMosaicBucketGeometryArtifact(
            timeBucket = snapshot.timeBucket,
            groupMode = groupMode,
            columnCount = columnCount,
            familiesKey = familiesKey,
            assetFingerprint = fingerprint,
            geometryRequest = request,
            placeholderHeight = timelineBucketGeometryHeight(
                groupSize = groupSize,
                sectionGeometries = geometries,
                spacing = request.spacing
            ),
            displayItemCount = timelineBucketGeometryDisplayItemCount(
                groupSize = groupSize,
                sectionGeometries = geometries
            ),
            updatedAt = updatedAt
        )

        return TimelineMosaicBucketArtifacts(
            timeBucket = snapshot.timeBucket,
            groupMode = groupMode,
            columnCount = columnCount,
            familiesKey = familiesKey,
            assignments = assignments,
            displayCache = displayCache,
            sectionGeometry = sectionGeometry,
            bucketGeometry = bucketGeometry
        )
    }
}
