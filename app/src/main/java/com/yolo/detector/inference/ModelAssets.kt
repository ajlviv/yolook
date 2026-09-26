package com.yolo.detector.inference

import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.DEFAULT_MODEL_PROFILE_ID
import com.yolo.detector.data.YOLOE_LABELS
import com.yolo.detector.data.YOLOE_PROFILE_ID

/**
 * Names and presence checks for the on-device TFLite models packaged under `src/main/assets`.
 *
 * The active model is chosen explicitly (see `SettingsRepository.modelProfileId`) and
 * resolved here against the assets actually present in the APK, so a missing or renamed
 * asset degrades to [DEFAULT_PROFILE] instead of failing to load.
 */
object ModelAssets {
    const val FILE_NAME = "yolo11n.tflite"
    const val YOLOE_FILE_NAME = "yoloe-11s-seg.tflite"

    val DEFAULT_PROFILE = ModelProfile(
        id = DEFAULT_MODEL_PROFILE_ID,
        assetName = FILE_NAME,
        labels = COCO_LABELS,
    ).also { it.validate() }

    /**
     * YOLOE 11s segmentation export with the `eye` / `smile` / `nose` vocabulary baked
     * in at export time (`PromptMode.STATIC` — the graph takes only an image input).
     *
     * Contract read from `yoloe-11s-seg.tflite`:
     * input `serving_default_args_0` `[1,3,640,640]` float32, outputs
     * `[1, 4 + 3 + 32, 8400]` and `[1, 32, 160, 160]` float32.
     */
    val YOLOE_PROFILE = ModelProfile(
        id = YOLOE_PROFILE_ID,
        assetName = YOLOE_FILE_NAME,
        task = ModelTask.SEGMENTATION,
        labels = YOLOE_LABELS,
        promptMode = PromptMode.STATIC,
        maskChannels = 32,
        protoSize = 160,
        recommendedConfidenceThreshold = 0.10f,
    ).also { it.validate() }

    private val registeredProfiles = linkedSetOf(DEFAULT_PROFILE, YOLOE_PROFILE)

    /** All profiles in registration order (default first). */
    val allProfiles: List<ModelProfile>
        get() = registeredProfiles.toList()

    fun register(profile: ModelProfile) {
        profile.validateForCurrentUi()
        check(registeredProfiles.none { it.assetName == profile.assetName }) {
            "A model profile is already registered for ${profile.assetName}"
        }
        registeredProfiles += profile
    }

    fun isListed(assetNames: Array<String>?, profile: ModelProfile = DEFAULT_PROFILE): Boolean =
        assetNames?.contains(profile.assetName) == true

    fun profileForAsset(assetName: String): ModelProfile? =
        registeredProfiles.firstOrNull { it.assetName == assetName }

    fun profileForId(id: String): ModelProfile? =
        registeredProfiles.firstOrNull { it.id == id }

    /** Profiles whose asset is actually packaged, in registration order. */
    fun availableProfiles(assetNames: Array<String>?): List<ModelProfile> =
        registeredProfiles.filter { isListed(assetNames, it) }

    /**
     * Resolves the model to run: the explicitly requested [selectedId] when its asset is
     * present, otherwise [DEFAULT_PROFILE] when present, otherwise null (no usable model).
     */
    fun resolveProfile(assetNames: Array<String>?, selectedId: String): ModelProfile? =
        profileForId(selectedId)?.takeIf { isListed(assetNames, it) }
            ?: DEFAULT_PROFILE.takeIf { isListed(assetNames, it) }
}
