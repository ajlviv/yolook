package com.yolo.detector.inference

import com.yolo.detector.data.COCO_LABELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAssetsTest {

    private val yolo11Asset = arrayOf("README.md", ModelAssets.FILE_NAME)
    private val yoloeAsset = arrayOf("README.md", ModelAssets.YOLOE_FILE_NAME)

    @Test
    fun isListed_trueWhenModelFilePresent() {
        assertTrue(ModelAssets.isListed(yolo11Asset))
    }

    @Test
    fun isListed_falseWhenModelFileMissing() {
        assertFalse(ModelAssets.isListed(arrayOf("README.md")))
    }

    @Test
    fun isListed_falseWhenAssetListIsNull() {
        assertFalse(ModelAssets.isListed(null))
    }

    @Test
    fun yoloeProfileIsRegistered() {
        assertTrue(ModelAssets.profileForAsset(ModelAssets.YOLOE_FILE_NAME) === ModelAssets.YOLOE_PROFILE)
    }

    @Test
    fun availableProfiles_listsBothWhenBothAssetsPresent() {
        val ids = ModelAssets.availableProfiles(arrayOf(ModelAssets.FILE_NAME, ModelAssets.YOLOE_FILE_NAME)).map { it.id }

        assertEquals(listOf(ModelAssets.DEFAULT_PROFILE.id, ModelAssets.YOLOE_PROFILE.id), ids)
    }

    @Test
    fun availableProfiles_omitsMissingAssets() {
        val ids = ModelAssets.availableProfiles(yolo11Asset).map { it.id }

        assertEquals(listOf(ModelAssets.DEFAULT_PROFILE.id), ids)
    }

    @Test
    fun resolveProfile_honoursExplicitSelection() {
        val profile = ModelAssets.resolveProfile(
            arrayOf(ModelAssets.FILE_NAME, ModelAssets.YOLOE_FILE_NAME),
            ModelAssets.YOLOE_PROFILE.id,
        )

        assertTrue(profile === ModelAssets.YOLOE_PROFILE)
    }

    @Test
    fun resolveProfile_fallsBackToDefaultWhenSelectionIsBlank() {
        val profile = ModelAssets.resolveProfile(
            arrayOf(ModelAssets.FILE_NAME, ModelAssets.YOLOE_FILE_NAME),
            "",
        )

        assertTrue(profile === ModelAssets.DEFAULT_PROFILE)
    }

    @Test
    fun resolveProfile_fallsBackToDefaultWhenSelectedAssetIsMissing() {
        val profile = ModelAssets.resolveProfile(yolo11Asset, ModelAssets.YOLOE_PROFILE.id)

        assertTrue(profile === ModelAssets.DEFAULT_PROFILE)
    }

    @Test
    fun resolveProfile_returnsNullWhenNoKnownAssetIsPresent() {
        assertNull(ModelAssets.resolveProfile(arrayOf("unregistered.tflite"), ""))
    }

    @Test
    fun resolveProfile_returnsNullWhenOnlyUnknownSelectionResolves() {
        assertNull(ModelAssets.resolveProfile(yoloeAsset, "does-not-exist"))
    }

    @Test
    fun registerProfile_makesProfileSelectable() {
        val profile = ModelProfile(
            id = "test-static",
            assetName = "test-static.tflite",
            labels = COCO_LABELS,
            promptMode = PromptMode.STATIC,
        )

        ModelAssets.register(profile)

        assertTrue(ModelAssets.resolveProfile(arrayOf(profile.assetName), profile.id) === profile)
    }

    @Test
    fun registerProfile_doesNotStealSelectionFromDefault() {
        val profile = ModelProfile(
            id = "registration-side-effect",
            assetName = "registration-side-effect.tflite",
            labels = COCO_LABELS,
            promptMode = PromptMode.STATIC,
        )

        ModelAssets.register(profile)

        // Presence of another model must not silently change which model runs.
        assertTrue(
            ModelAssets.resolveProfile(
                arrayOf(ModelAssets.FILE_NAME, profile.assetName),
                ModelAssets.DEFAULT_PROFILE.id,
            ) === ModelAssets.DEFAULT_PROFILE,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun registerProfile_rejectsDuplicateAssetName() {
        ModelAssets.register(
            ModelProfile(
                id = "duplicate-asset",
                assetName = ModelAssets.FILE_NAME,
                labels = COCO_LABELS,
            )
        )
    }
}
