package com.yolo.detector.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Class filters are stored per model profile because a class ID only means something
 * inside one model's label space. These tests pin that isolation: switching models must
 * never carry one model's IDs over as if they named the same classes in another.
 */
class InferenceSettingsTest {

    @Test
    fun defaultsToDefaultProfileWithNoStoredFilter() {
        val settings = InferenceSettings()

        assertEquals(DEFAULT_MODEL_PROFILE_ID, settings.modelProfileId)
        // Nothing stored: every class the profile declares is enabled.
        assertEquals((0 until COCO_LABELS.size).toSet(), settings.classFilterFor(COCO_LABELS.size))
    }

    @Test
    fun resolvesFilterForTheActiveProfile() {
        val settings = InferenceSettings(
            modelProfileId = "yoloe-11s-seg",
            classFilters = mapOf("yoloe-11s-seg" to setOf(0, 2)),
        )

        assertEquals(setOf(0, 2), settings.classFilterFor(3))
    }

    @Test
    fun keepsEachProfilesFilterSeparate() {
        val stored = mapOf(
            DEFAULT_MODEL_PROFILE_ID to setOf(1, 2),
            "yoloe-11s-seg" to setOf(0),
        )

        val onCoco = InferenceSettings(modelProfileId = DEFAULT_MODEL_PROFILE_ID, classFilters = stored)
        val onYoloe = InferenceSettings(modelProfileId = "yoloe-11s-seg", classFilters = stored)

        assertEquals(setOf(1, 2), onCoco.classFilterFor(COCO_LABELS.size))
        assertEquals(setOf(0), onYoloe.classFilterFor(3))
    }

    @Test
    fun unconfiguredProfileFallsBackToAllOfItsOwnClasses() {
        // A profile the user has never narrowed must not inherit another profile's IDs.
        val settings = InferenceSettings(
            modelProfileId = "yoloe-11s-seg",
            classFilters = mapOf(DEFAULT_MODEL_PROFILE_ID to setOf(1, 2)),
        )

        assertEquals((0 until 3).toSet(), settings.classFilterFor(3))
    }

    @Test
    fun emptyStoredFilterEnablesEverythingRatherThanNothing() {
        // An empty set means "unset", not "detect nothing": a stored empty filter would
        // otherwise make a model silently output nothing after a partial write.
        val settings = InferenceSettings(classFilters = mapOf(DEFAULT_MODEL_PROFILE_ID to emptySet()))

        assertEquals((0 until COCO_LABELS.size).toSet(), settings.classFilterFor(COCO_LABELS.size))
    }

    @Test
    fun aNarrowedProfileDoesNotNarrowTheOthers() {
        val narrowed = InferenceSettings(
            modelProfileId = DEFAULT_MODEL_PROFILE_ID,
            classFilters = mapOf(DEFAULT_MODEL_PROFILE_ID to setOf(3)),
        )
        // Switching to a model with no stored filter of its own yields all of *its*
        // classes, not the single class the previous model was narrowed to.
        val switched = narrowed.copy(modelProfileId = "yoloe-11s-seg")

        assertEquals(setOf(3), narrowed.classFilterFor(COCO_LABELS.size))
        assertEquals((0 until 3).toSet(), switched.classFilterFor(3))
    }
}

/**
 * Labels are supplied by the caller rather than baked into a global list, so a YOLOE
 * detection's class ID is resolved against the YOLOE vocabulary.
 */
class ModelLabelsTest {

    @Test
    fun resolvesAgainstTheGivenVocabulary() {
        assertEquals("person", labelFor(0, COCO_LABELS))
        assertEquals("eye", labelFor(0, YOLOE_LABELS))
        assertEquals("nose", labelFor(2, YOLOE_LABELS))
    }

    @Test
    fun fallsBackForOutOfRangeClassId() {
        assertEquals("unknown", labelFor(999, YOLOE_LABELS))
        assertEquals("unknown", labelFor(-1, COCO_LABELS))
    }

    @Test
    fun fallsBackForEmptyVocabulary() {
        assertEquals("unknown", labelFor(0, emptyList()))
    }
}
