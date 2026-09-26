package com.yolo.detector.inference

import com.yolo.detector.data.COCO_LABELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelProfileTest {

    // ── Signatures read from the real .tflite files ──────────────────────────

    private val yolo11Signature = ModelSignature(
        inputs = listOf(TensorSignature("serving_default_args_0", listOf(1, 3, 640, 640), TensorDataType.FLOAT32)),
        outputs = listOf(TensorSignature("serving_default_output_0_output", listOf(1, 84, 8400), TensorDataType.FLOAT32)),
    )

    private val yoloeSignature = ModelSignature(
        inputs = listOf(TensorSignature("serving_default_args_0", listOf(1, 3, 640, 640), TensorDataType.FLOAT32)),
        outputs = listOf(
            TensorSignature("serving_default_output_0_output", listOf(1, 39, 8400), TensorDataType.FLOAT32),
            TensorSignature("serving_default_output_1_output", listOf(1, 32, 160, 160), TensorDataType.FLOAT32),
        ),
    )

    @Test
    fun yolo11ProfileMatchesItsRealSignature() {
        ModelAssets.DEFAULT_PROFILE.validateSignature(yolo11Signature)
    }

    @Test
    fun yoloeProfileMatchesItsRealSignature() {
        ModelAssets.YOLOE_PROFILE.validateSignature(yoloeSignature)
    }

    @Test
    fun yoloeProfileDecodesThreeClassesPlusMasks() {
        val profile = ModelAssets.YOLOE_PROFILE

        assertEquals(ModelTask.SEGMENTATION, profile.task)
        assertEquals(3, profile.numClasses)
        assertEquals(32, profile.maskChannels)
        assertEquals(160, profile.protoSize)
        assertEquals(2, profile.expectedOutputShapes.size)
    }

    @Test
    fun yoloeProfileEmitsNormalizedBoxes() {
        assertEquals(BoxScale.NORMALIZED, ModelAssets.YOLOE_PROFILE.boxScale)
        assertEquals(BoxScale.NORMALIZED, ModelAssets.DEFAULT_PROFILE.boxScale)
    }

    @Test
    fun detectionProfileRejectsSegmentationOutputs() {
        assertThrows(ModelContractException::class.java) {
            ModelAssets.DEFAULT_PROFILE.validateSignature(yoloeSignature)
        }
    }

    @Test
    fun segmentationProfileRejectsDetectionOutput() {
        assertThrows(ModelContractException::class.java) {
            ModelAssets.YOLOE_PROFILE.validateSignature(yolo11Signature)
        }
    }

    @Test
    fun rejectsSegmentationProfileWithoutMaskChannels() {
        val profile = ModelProfile(
            id = "seg-no-mask",
            assetName = "seg-no-mask.tflite",
            task = ModelTask.SEGMENTATION,
            labels = listOf("person"),
        )

        assertThrows(ModelContractException::class.java) { profile.validate() }
    }

    @Test
    fun rejectsDetectionProfileWithMaskChannels() {
        val profile = ModelProfile(
            id = "detect-with-mask",
            assetName = "detect-with-mask.tflite",
            maskChannels = 32,
            labels = listOf("person"),
        )

        assertThrows(ModelContractException::class.java) { profile.validate() }
    }

    @Test
    fun rejectsNonSquarePrototypes() {
        val profile = ModelProfile(
            id = "seg-odd-proto",
            assetName = "seg-odd-proto.tflite",
            task = ModelTask.SEGMENTATION,
            maskChannels = 32,
            labels = listOf("person"),
        )

        assertThrows(ModelContractException::class.java) {
            profile.validateSignature(
                ModelSignature(
                    inputs = listOf(TensorSignature("i", listOf(1, 3, 640, 640), TensorDataType.FLOAT32)),
                    outputs = listOf(
                        TensorSignature("o0", listOf(1, 37, 8400), TensorDataType.FLOAT32),
                        TensorSignature("o1", listOf(1, 32, 80, 160), TensorDataType.FLOAT32),
                    ),
                )
            )
        }
    }

    // ── Generic validation ───────────────────────────────────────────────────

    @Test
    fun genericProfileValidationAcceptsStaticNonCocoVocabulary() {
        val profile = ModelProfile(
            id = "yoloe-static",
            assetName = "yoloe.tflite",
            labels = listOf("forklift", "helmet"),
            promptMode = PromptMode.STATIC,
        )
        val signature = ModelSignature(
            inputs = listOf(TensorSignature("input", listOf(1, 3, 640, 640), TensorDataType.FLOAT32)),
            outputs = listOf(TensorSignature("output", listOf(1, 6, 8400), TensorDataType.FLOAT32)),
        )

        profile.validateSignature(signature)

        assertEquals(2, profile.numClasses)
    }

    @Test
    fun acceptsProfileDefinedLabelsForCurrentUi() {
        val profile = ModelProfile(
            id = "yoloe-static",
            assetName = "yoloe.tflite",
            labels = listOf("forklift", "helmet"),
        )

        profile.validateForCurrentUi()
    }

    @Test
    fun rejectsRuntimePromptProfile() {
        val profile = ModelProfile(
            id = "yoloe-runtime",
            assetName = "yoloe.tflite",
            labels = listOf("person"),
            promptMode = PromptMode.TEXT,
        )

        assertThrows(ModelContractException::class.java) { profile.validate() }
    }

    @Test
    fun rejectsExtraInputTensors() {
        val signature = ModelSignature(
            inputs = listOf(
                TensorSignature("image", listOf(1, 3, 640, 640), TensorDataType.FLOAT32),
                TensorSignature("prompt", listOf(1, 512), TensorDataType.FLOAT32),
            ),
            outputs = listOf(TensorSignature("output", listOf(1, 84, 8400), TensorDataType.FLOAT32)),
        )

        assertThrows(ModelContractException::class.java) {
            ModelAssets.DEFAULT_PROFILE.validateSignature(signature)
        }
    }

    @Test
    fun rejectsUnsupportedInputDataType() {
        val signature = ModelSignature(
            inputs = listOf(TensorSignature("input", listOf(1, 3, 640, 640), TensorDataType.UINT8)),
            outputs = listOf(TensorSignature("output", listOf(1, 84, 8400), TensorDataType.FLOAT32)),
        )

        assertThrows(ModelContractException::class.java) {
            ModelAssets.DEFAULT_PROFILE.validateSignature(signature)
        }
    }

    @Test
    fun rejectsMismatchedClassCount() {
        val signature = ModelSignature(
            inputs = listOf(TensorSignature("input", listOf(1, 3, 640, 640), TensorDataType.FLOAT32)),
            outputs = listOf(TensorSignature("output", listOf(1, 85, 8400), TensorDataType.FLOAT32)),
        )

        assertThrows(ModelContractException::class.java) {
            ModelAssets.DEFAULT_PROFILE.validateSignature(signature)
        }
    }

    @Test
    fun rejectsUnsupportedPreprocessing() {
        val profile = ModelProfile(
            id = "bgr-profile",
            assetName = "bgr.tflite",
            labels = listOf("person"),
            colorOrder = ColorOrder.BGR,
        )

        assertThrows(ModelContractException::class.java) { profile.validate() }
    }

    // ── Current-UI capability limits ──────────────────────────────────────────

    @Test
    fun rejectsUnsupportedInputSizeForCurrentUi() {
        val profile = ModelProfile(
            id = "unsupported-input-size",
            assetName = "unsupported-input-size.tflite",
            inputSize = 512,
            labels = COCO_LABELS,
        )

        assertThrows(ModelContractException::class.java) { profile.validateForCurrentUi() }
    }

    @Test
    fun rejectsUnsupportedPredictionCountForCurrentUi() {
        val profile = ModelProfile(
            id = "unsupported-prediction-count",
            assetName = "unsupported-prediction-count.tflite",
            numBoxes = 1,
            labels = COCO_LABELS,
        )

        assertThrows(ModelContractException::class.java) { profile.validateForCurrentUi() }
    }

    @Test
    fun rejectsExtremeInputSizeForCurrentUi() {
        val profile = ModelProfile(
            id = "extreme-input-size",
            assetName = "extreme-input-size.tflite",
            inputSize = Int.MAX_VALUE,
            labels = COCO_LABELS,
        )

        assertThrows(ModelContractException::class.java) { profile.validateForCurrentUi() }
    }

    @Test
    fun rejectsExtremePredictionCountForCurrentUi() {
        val profile = ModelProfile(
            id = "extreme-prediction-count",
            assetName = "extreme-prediction-count.tflite",
            numBoxes = Int.MAX_VALUE,
            labels = COCO_LABELS,
        )

        assertThrows(ModelContractException::class.java) { profile.validateForCurrentUi() }
    }
}
