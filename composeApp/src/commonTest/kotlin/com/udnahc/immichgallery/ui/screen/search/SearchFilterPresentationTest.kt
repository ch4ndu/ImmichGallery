package com.udnahc.immichgallery.ui.screen.search

import com.udnahc.immichgallery.ui.util.dateToPickerMillis
import com.udnahc.immichgallery.ui.util.dateFromPickerMillis
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.model.RatingConstraint
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchLoadState
import com.udnahc.immichgallery.domain.model.SearchMediaType
import com.udnahc.immichgallery.domain.model.SearchOptionalValue
import com.udnahc.immichgallery.domain.model.SearchPreferenceCapabilities
import com.udnahc.immichgallery.domain.model.SearchSuggestion
import com.udnahc.immichgallery.domain.model.SearchTag
import com.udnahc.immichgallery.domain.model.SearchVisibility
import com.udnahc.immichgallery.domain.model.TagConstraint
import com.udnahc.immichgallery.domain.model.TakenDateRange
import com.udnahc.immichgallery.domain.model.valueOrNull
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchFilterPresentationTest {
    @Test
    fun peopleFilteringIsLocalCaseInsensitiveAndKeepsStableDuplicateIdentity() {
        val options = listOf(
            person("p1", "Alex"),
            person("p2", ""),
            person("p3", "alex")
        )

        assertEquals(
            listOf("p1", "p3"),
            projectPeopleOptions(options, selectedIds = listOf("p2"), query = "ALEX")
                .map(Person::id)
        )
        assertEquals(
            listOf("p2", "p1", "p3"),
            projectPeopleOptions(options, selectedIds = listOf("p2"), query = "")
                .map(Person::id)
        )
    }

    @Test
    fun albumsAndTagsUseSelectedFirstStableOrderingOnlyForBlankQueries() {
        val albums = listOf(album("a1", "Trips"), album("a2", "Family"))
        val tags = listOf(tag("t1", "Travel/Beach"), tag("t2", "Family"))

        assertEquals(
            listOf("a2", "a1"),
            projectAlbumOptions(albums, listOf("a2"), "").map(Album::id)
        )
        assertEquals(
            listOf("t1"),
            projectTagOptions(tags, listOf("t2"), "beach").map(SearchTag::id)
        )
    }

    @Test
    fun summariesRetainUnavailableIdsAndCompactAdditionalSelections() {
        assertEquals(
            SearchSelectionSummary("Unavailable selection", 1),
            selectionSummary(
                selectedIds = listOf("missing", "p2"),
                options = listOf("p2" to "Jordan"),
                unavailableLabel = "Unavailable selection"
            )
        )
    }

    @Test
    fun draftCancelRestoresAppliedAndApplyProjectsOneNormalizedSnapshot() {
        val applied = SearchCriteria(personIds = listOf("p1"))
        val draft = SearchFilterDraftState(applied)
            .edit(
                applied.copy(
                    personIds = listOf("p1", "p2", "p2"),
                    albumIds = listOf("a1"),
                    isNotInAlbum = true
                )
            )

        assertEquals(applied, draft.cancel())
        val submitted = draft.apply()
        assertEquals(listOf("p1", "p2"), submitted?.personIds)
        assertTrue(submitted?.albumIds.isNullOrEmpty())
        assertEquals(true, submitted?.isNotInAlbum)
    }

    @Test
    fun calendarDatesRoundTripWithoutChangingTheirWallClockLabel() {
        listOf(
            LocalDate(2024, 2, 29),
            LocalDate(2026, 3, 8),
            LocalDate(2026, 11, 1)
        ).forEach { date ->
            assertEquals(date, dateFromPickerMillis(dateToPickerMillis(date)))
        }
    }

    @Test
    fun startOnlyEndOnlySameDayAndLeapDayDraftsCanApply() {
        listOf(
            TakenDateRange(start = LocalDate(2024, 2, 29)),
            TakenDateRange(endInclusive = LocalDate(2024, 2, 29)),
            TakenDateRange(
                start = LocalDate(2024, 2, 29),
                endInclusive = LocalDate(2024, 2, 29)
            )
        ).forEach { range ->
            val draft = SearchFilterDraftState(SearchCriteria()).edit(
                SearchCriteria(takenDateRange = range)
            )

            assertEquals(SearchDateRangeValidation.VALID, validateSearchDateRange(range))
            assertTrue(draft.canApply)
            assertEquals(range, draft.apply()?.takenDateRange)
        }
    }

    @Test
    fun reversedCalendarRangeRemainsInvalidAndCannotApply() {
        val range = TakenDateRange(
            start = LocalDate(2026, 11, 2),
            endInclusive = LocalDate(2026, 3, 8)
        )
        val draft = SearchFilterDraftState(SearchCriteria()).edit(
            SearchCriteria(takenDateRange = range)
        )

        assertEquals(SearchDateRangeValidation.END_BEFORE_START, validateSearchDateRange(range))
        assertFalse(draft.canApply)
        assertEquals(range, draft.draftCriteria.takenDateRange)
        assertNull(draft.apply())
    }

    @Test
    fun unknownSuggestionAppearsOnlyWhenLoadedOrCurrentlySelected() {
        val exact = SearchOptionalValue.Exact("Canon")

        assertFalse(shouldShowUnknownSuggestion(emptyList(), exact))
        assertTrue(
            shouldShowUnknownSuggestion(
                listOf(SearchSuggestion.Value("Canon"), SearchSuggestion.Unknown),
                exact
            )
        )
        val failedWithRetainedUnknown = SearchLoadState.Error(
            previous = listOf(SearchSuggestion.Unknown, SearchSuggestion.Value("Canon"))
        )
        assertTrue(
            shouldShowUnknownSuggestion(
                failedWithRetainedUnknown.valueOrNull().orEmpty(),
                exact
            )
        )
        assertTrue(shouldShowUnknownSuggestion(emptyList(), SearchOptionalValue.Unknown))
    }

    @Test
    fun dependentChoicesAndMediaConflictsClearOnlyInvalidDescendants() {
        val criteria = SearchCriteria(
            country = SearchOptionalValue.Exact("US"),
            state = SearchOptionalValue.Exact("Texas"),
            city = SearchOptionalValue.Exact("Austin"),
            make = SearchOptionalValue.Exact("Fujifilm"),
            model = SearchOptionalValue.Exact("X-T5"),
            lensModel = SearchOptionalValue.Exact("23mm"),
            motionOnly = true,
            mediaType = SearchMediaType.IMAGE
        )

        val countryChanged = criteria.withCountry(SearchOptionalValue.Exact("Canada"))
        assertEquals(SearchOptionalValue.Any, countryChanged.state)
        assertEquals(SearchOptionalValue.Any, countryChanged.city)
        assertEquals(criteria.make, countryChanged.make)

        val makeChanged = criteria.withCameraMake(SearchOptionalValue.Exact("Sony"))
        assertEquals(SearchOptionalValue.Any, makeChanged.model)
        assertEquals(SearchOptionalValue.Any, makeChanged.lensModel)
        assertEquals(criteria.country, makeChanged.country)

        val video = criteria.withMediaType(SearchMediaType.VIDEO)
        assertFalse(video.motionOnly)
        assertEquals(SearchMediaType.VIDEO, video.mediaType)
        assertEquals(SearchMediaType.IMAGE, video.withMotionOnly(true).mediaType)
    }

    @Test
    fun optionalCategoriesAppearOnlyAfterPositivePreferences() {
        val baseline = visibleSearchFilterTargets(null)
        assertFalse(SearchFilterTarget.TAGS in baseline)
        assertFalse(SearchFilterTarget.RATING in baseline)

        val enabled = visibleSearchFilterTargets(
            SearchPreferenceCapabilities(tagsEnabled = true, ratingsEnabled = true)
        )
        assertTrue(SearchFilterTarget.TAGS in enabled)
        assertTrue(SearchFilterTarget.RATING in enabled)
    }

    @Test
    fun clearTargetsRemoveOnlyTheirOwnedCriteria() {
        val criteria = SearchCriteria(
            personIds = listOf("p1"),
            albumIds = listOf("a1"),
            tags = TagConstraint.Selected(listOf("t1")),
            rating = RatingConstraint.Stars(4),
            isFavorite = true,
            visibility = SearchVisibility.ARCHIVE
        )

        assertTrue(criteria.clearedFor(SearchFilterTarget.ALBUMS).albumIds.isEmpty())
        assertEquals(criteria.personIds, criteria.clearedFor(SearchFilterTarget.ALBUMS).personIds)
        assertEquals(RatingConstraint.Any, criteria.clearedFor(SearchFilterTarget.RATING).rating)
        assertFalse(criteria.clearedFor(SearchFilterTarget.DISPLAY).isFavorite)
    }

    @Test
    fun activeCountUsesCategoriesInsteadOfUnboundedEntityCounts() {
        val criteria = SearchCriteria(
            personIds = listOf("p1", "p2"),
            albumIds = listOf("a1", "a2"),
            tags = TagConstraint.Untagged,
            rating = RatingConstraint.Unrated
        )

        assertEquals(4, activeSearchFilterCount(criteria))
    }

    private fun person(id: String, name: String) = Person(id, name, "https://example.test/$id")

    private fun album(id: String, name: String) = Album(id, name, 1, "https://example.test/$id")

    private fun tag(id: String, value: String) = SearchTag(id, null, value.substringAfterLast('/'), value, null)
}
