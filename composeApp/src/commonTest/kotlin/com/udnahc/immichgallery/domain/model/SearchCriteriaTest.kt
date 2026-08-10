package com.udnahc.immichgallery.domain.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchCriteriaTest {
    @Test
    fun emptyDetectionDistinguishesFilterOnlySearch() {
        assertTrue(SearchCriteria(query = "  ").normalized().isEmpty)
        assertFalse(SearchCriteria(isFavorite = true).normalized().isEmpty)
        assertFalse(SearchCriteria(tags = TagConstraint.Untagged).normalized().isEmpty)
    }

    @Test
    fun normalizationTrimsTextAndStablyDeduplicatesIds() {
        val criteria = SearchCriteria(
            query = "  sunset  ",
            personIds = listOf(" p2 ", "p1", "p2", ""),
            albumIds = listOf("a1", " a1 ", "a2"),
            tags = TagConstraint.Selected(listOf("t2", "t1", "t2"))
        ).normalized()

        assertEquals("sunset", criteria.query)
        assertEquals(listOf("p2", "p1"), criteria.personIds)
        assertEquals(listOf("a1", "a2"), criteria.albumIds)
        assertEquals(TagConstraint.Selected(listOf("t2", "t1")), criteria.tags)
    }

    @Test
    fun optionalCameraAndLocationValuesKeepAnyUnknownAndExactDistinct() {
        val criteria = SearchCriteria(
            country = SearchOptionalValue.Any,
            state = SearchOptionalValue.Unknown,
            city = SearchOptionalValue.Exact("  Austin "),
            make = SearchOptionalValue.Unknown,
            model = SearchOptionalValue.Exact(" X-T5 "),
            lensModel = SearchOptionalValue.Any
        ).normalized()

        assertEquals(SearchOptionalValue.Any, criteria.country)
        assertEquals(SearchOptionalValue.Unknown, criteria.state)
        assertEquals(SearchOptionalValue.Exact("Austin"), criteria.city)
        assertEquals(SearchOptionalValue.Unknown, criteria.make)
        assertEquals(SearchOptionalValue.Exact("X-T5"), criteria.model)
        assertEquals(SearchOptionalValue.Any, criteria.lensModel)
    }

    @Test
    fun tagAndRatingConstraintsKeepEverySemanticStateDistinct() {
        assertEquals(TagConstraint.Any, SearchCriteria().normalized().tags)
        assertEquals(TagConstraint.Untagged, SearchCriteria(tags = TagConstraint.Untagged).normalized().tags)
        assertEquals(
            TagConstraint.Selected(listOf("tag")),
            SearchCriteria(tags = TagConstraint.Selected(listOf("tag"))).normalized().tags
        )
        assertEquals(RatingConstraint.Any, SearchCriteria().normalized().rating)
        assertEquals(RatingConstraint.Unrated, SearchCriteria(rating = RatingConstraint.Unrated).normalized().rating)
        assertEquals(RatingConstraint.Stars(5), SearchCriteria(rating = RatingConstraint.Stars(5)).normalized().rating)
        assertFailsWith<IllegalArgumentException> { RatingConstraint.Stars(0) }
        assertFailsWith<IllegalArgumentException> { RatingConstraint.Stars(6) }
    }

    @Test
    fun motionOnlyNormalizesContradictoryVideoToImage() {
        val criteria = SearchCriteria(
            mediaType = SearchMediaType.VIDEO,
            motionOnly = true
        ).normalized()

        assertEquals(SearchMediaType.IMAGE, criteria.mediaType)
        assertTrue(criteria.motionOnly)
    }

    @Test
    fun albumAndNotInAlbumHelpersResolveTheAppliedIntent() {
        val notInAlbum = SearchCriteria(albumIds = listOf("a1"))
            .withNotInAlbum(true)
        val albums = notInAlbum.withAlbumIds(listOf("a2"))

        assertTrue(notInAlbum.isNotInAlbum)
        assertEquals(emptyList(), notInAlbum.albumIds)
        assertFalse(albums.isNotInAlbum)
        assertEquals(listOf("a2"), albums.albumIds)
    }

    @Test
    fun normalizationResolvesDirectAlbumAndNotInAlbumConflict() {
        val criteria = SearchCriteria(
            albumIds = listOf("album-1"),
            isNotInAlbum = true
        ).normalized()

        assertTrue(criteria.isNotInAlbum)
        assertEquals(emptyList(), criteria.albumIds)
    }

    @Test
    fun reversedDateRangeNormalizesToInclusiveOrder() {
        val criteria = SearchCriteria(
            takenDateRange = TakenDateRange(
                start = LocalDate(2026, 11, 2),
                endInclusive = LocalDate(2026, 3, 8)
            )
        ).normalized()

        assertEquals(LocalDate(2026, 3, 8), criteria.takenDateRange.start)
        assertEquals(LocalDate(2026, 11, 2), criteria.takenDateRange.endInclusive)
    }

    @Test
    fun endpointSelectionIsPureAndFilterOnlyAlwaysUsesMetadata() {
        assertEquals(
            SearchEndpoint.SMART,
            selectSearchEndpoint(SearchCriteria(query = "beach").normalized())
        )
        listOf(SearchTextMode.FILENAME, SearchTextMode.DESCRIPTION, SearchTextMode.OCR).forEach { mode ->
            assertEquals(
                SearchEndpoint.METADATA,
                selectSearchEndpoint(SearchCriteria(query = "beach", textMode = mode).normalized())
            )
        }
        assertEquals(
            SearchEndpoint.METADATA,
            selectSearchEndpoint(SearchCriteria(isFavorite = true).normalized())
        )
        assertFailsWith<IllegalArgumentException> { selectSearchEndpoint(SearchCriteria()) }
    }

    @Test
    fun submittedSessionFreezesNormalizedCriteriaEndpointAndPageState() {
        val criteria = SearchCriteria(
            query = "beach",
            personIds = listOf("person-1")
        ).normalized()
        val session = SearchSession(
            criteria = criteria,
            endpoint = SearchEndpoint.SMART,
            generation = 4L,
            currentPage = 3,
            nextPage = 7,
            consumedPages = setOf(1, 3)
        )

        assertEquals(criteria, session.criteria)
        assertEquals(SearchEndpoint.SMART, session.endpoint)
        assertEquals(4L, session.generation)
        assertEquals(3, session.currentPage)
        assertEquals(7, session.nextPage)
        assertEquals(setOf(1, 3), session.consumedPages)
    }

    @Test
    fun submittedSessionRejectsInvalidIdentityAndPageState() {
        val contextCriteria = SearchCriteria(query = "beach").normalized()

        assertFailsWith<IllegalArgumentException> {
            SearchSession(
                criteria = contextCriteria,
                endpoint = SearchEndpoint.METADATA,
                generation = 1L
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SearchSession(
                criteria = SearchCriteria(),
                endpoint = SearchEndpoint.METADATA,
                generation = 1L
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SearchSession(
                criteria = contextCriteria,
                endpoint = SearchEndpoint.SMART,
                generation = 0L
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SearchSession(
                criteria = contextCriteria,
                endpoint = SearchEndpoint.SMART,
                generation = 1L,
                nextPage = -1
            )
        }
    }
}
