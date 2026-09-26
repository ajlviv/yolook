package com.yolo.detector.inference

import org.tensorflow.lite.DataType

enum class ModelTask {
    DETECTION,
    SEGMENTATION,
}

enum class ModelLayout {
    NCHW,
    NHWC,
    CHANNELS_FIRST,
    CHANNELS_LAST,
}

enum class TensorDataType {
    FLOAT32,
    FLOAT16,
    UINT8,
    INT8,
    INT32,
    INT64,
    BOOLEAN,
    UNKNOWN,
}

enum class PromptMode {
    NONE,
    STATIC,
    TEXT,
    VISUAL,
    PROMPT_FREE,
}

enum class ColorOrder {
    RGB,
    BGR,
}

enum class ValueRange {
    ZERO_TO_ONE,
    ZERO_TO_255,
}

enum class BoxFormat {
    CENTER_WIDTH_HEIGHT,
    XYXY,
}

/**
 * How a model's box channels are scaled.
 *
 * Ultralytics' LiteRT export wraps the head in `_NormalizeCoords`, which divides the
 * four box channels by the input size, so shipped exports already emit `[0, 1]`
 * coordinates. [MODEL_PIXELS] exists for hand-converted models that do not.
 */
enum class BoxScale {
    NORMALIZED,
    MODEL_PIXELS,
}

data class TensorSignature(
    val name: String = "",
    val shape: List<Int>,
    val dataType: TensorDataType,
)

data class ModelSignature(
    val inputs: List<TensorSignature>,
    val outputs: List<TensorSignature>,
)

data class ModelProfile(
    val id: String,
    val assetName: String,
    val task: ModelTask = ModelTask.DETECTION,
    val inputSize: Int = 640,
    val inputLayout: ModelLayout = ModelLayout.NCHW,
    val inputDataType: TensorDataType = TensorDataType.FLOAT32,
    val outputLayout: ModelLayout = ModelLayout.CHANNELS_FIRST,
    val outputDataType: TensorDataType = TensorDataType.FLOAT32,
    val numBoxes: Int = 8400,
    val labels: List<String>,
    val promptMode: PromptMode = PromptMode.NONE,
    val colorOrder: ColorOrder = ColorOrder.RGB,
    val valueRange: ValueRange = ValueRange.ZERO_TO_ONE,
    val boxFormat: BoxFormat = BoxFormat.CENTER_WIDTH_HEIGHT,
    val boxScale: BoxScale = BoxScale.NORMALIZED,

    /**
     * Suggested confidence threshold for this profile. YOLOE face models emit much
     * lower peak class scores than COCO detection models, so a shared threshold
     * either floods the screen with noise or hides every face.
     */
    val recommendedConfidenceThreshold: Float = 0.25f,

    /**
     * Mask-prototype channel count for [ModelTask.SEGMENTATION] profiles. Equals the
     * number of trailing channels in the primary output and the channel count of the
     * prototype output. Must be 0 for detection profiles.
     */
    val maskChannels: Int = 0,

    /**
     * Side length of the square mask-prototype output for [ModelTask.SEGMENTATION]
     * profiles (Ultralytics emits `inputSize / 4`). Must be 0 for detection profiles.
     */
    val protoSize: Int = 0,
) {

    val numClasses: Int
        get() = labels.size

    /** Channels in the primary output: boxes + classes, plus mask coefficients when segmenting. */
    val primaryChannels: Int
        get() = 4 + numClasses + maskChannels

    /** Offset of the first mask-coefficient channel within the primary output. */
    val maskChannelOffset: Int
        get() = 4 + numClasses

    val expectedInputShape: List<Int>
        get() = when (inputLayout) {
            ModelLayout.NCHW -> listOf(1, 3, inputSize, inputSize)
            ModelLayout.NHWC -> listOf(1, inputSize, inputSize, 3)
            else -> emptyList()
        }

    /**
     * Expected output tensor shapes, in interpreter order: the detection/segmentation
     * head first, then the mask prototypes for segmentation profiles.
     */
    val expectedOutputShapes: List<List<Int>>
        get() = buildList {
            when (outputLayout) {
                ModelLayout.CHANNELS_FIRST -> add(listOf(1, primaryChannels, numBoxes))
                ModelLayout.CHANNELS_LAST -> add(listOf(1, numBoxes, primaryChannels))
                else -> Unit
            }
            if (task == ModelTask.SEGMENTATION) add(listOf(1, maskChannels, protoSize, protoSize))
        }

    /** Primary (head) output shape, for logging. */
    val expectedOutputShape: List<Int>
        get() = expectedOutputShapes.firstOrNull().orEmpty()

    val expectedProtoShape: List<Int>
        get() = if (task == ModelTask.SEGMENTATION) listOf(1, maskChannels, protoSize, protoSize) else emptyList()

    fun validate() {
        reject(id.isBlank()) { "Model profile id must not be blank" }
        reject(assetName.isBlank()) { "Model asset name must not be blank" }
        reject(promptMode !in setOf(PromptMode.NONE, PromptMode.STATIC)) {
            "Runtime YOLOE prompts require a dedicated prompt-aware detector"
        }
        reject(inputSize <= 0) { "Input size must be positive" }
        reject(numBoxes <= 0) { "Box count must be positive" }
        reject(labels.isEmpty()) { "At least one class label is required" }
        reject(labels.any { it.isBlank() }) { "Class labels must not be blank" }
        reject(labels.distinct().size != labels.size) { "Class labels must be unique" }
        reject(inputDataType != TensorDataType.FLOAT32) {
            "Only float32 image input is supported"
        }
        reject(outputDataType != TensorDataType.FLOAT32) {
            "Only float32 detection output is supported"
        }
        reject(inputLayout !in setOf(ModelLayout.NCHW, ModelLayout.NHWC)) {
            "Input layout must be NCHW or NHWC"
        }
        reject(outputLayout !in setOf(ModelLayout.CHANNELS_FIRST, ModelLayout.CHANNELS_LAST)) {
            "Output layout must be channels-first or channels-last"
        }
        reject(colorOrder != ColorOrder.RGB) {
            "Only RGB input is supported"
        }
        reject(valueRange != ValueRange.ZERO_TO_ONE) {
            "Only [0, 1] input values are supported"
        }
        reject(boxFormat != BoxFormat.CENTER_WIDTH_HEIGHT) {
            "Only center-width-height boxes are supported"
        }
        if (task == ModelTask.SEGMENTATION) {
            reject(maskChannels <= 0) { "Segmentation profiles require a positive maskChannels" }
            reject(protoSize <= 0) { "Segmentation profiles require a positive protoSize" }
        } else {
            reject(maskChannels != 0) { "Only segmentation profiles may declare maskChannels" }
            reject(protoSize != 0) { "Only segmentation profiles may declare protoSize" }
        }
    }

    /**
     * Extra limits imposed by the current UI layer, which renders a single fixed-size
     * input and a fixed prediction count. Label vocabularies are *not* constrained —
     * the UI is driven by [labels].
     */
    fun validateForCurrentUi() {
        validate()
        reject(inputSize != 640) {
            "The current UI supports only 640x640 input"
        }
        reject(numBoxes != 8400) {
            "The current UI supports only 8400 predictions"
        }
    }

    fun validateSignature(signature: ModelSignature) {
        validate()
        reject(signature.inputs.size != 1) {
            "Expected one image input, found ${signature.inputs.size}"
        }

        val input = signature.inputs.single()
        reject(input.shape != expectedInputShape) {
            "Input shape ${input.shape} does not match $expectedInputShape"
        }
        reject(input.dataType != inputDataType) {
            "Input type ${input.dataType} does not match $inputDataType"
        }

        reject(signature.outputs.size != expectedOutputShapes.size) {
            "Expected ${expectedOutputShapes.size} output tensor(s), found ${signature.outputs.size}"
        }
        signature.outputs.forEachIndexed { index, output ->
            val expected = expectedOutputShapes[index]
            reject(output.shape != expected) {
                "Output $index shape ${output.shape} does not match $expected"
            }
            reject(output.dataType != outputDataType) {
                "Output $index type ${output.dataType} does not match $outputDataType"
            }
        }
    }

    private fun reject(condition: Boolean, message: () -> String) {
        if (condition) throw ModelContractException(message())
    }
}

class ModelContractException(message: String) : IllegalArgumentException(message)

fun tensorDataType(value: DataType): TensorDataType = when (value) {
    DataType.FLOAT32 -> TensorDataType.FLOAT32
    DataType.UINT8 -> TensorDataType.UINT8
    DataType.INT8 -> TensorDataType.INT8
    DataType.INT32 -> TensorDataType.INT32
    DataType.INT64 -> TensorDataType.INT64
    DataType.BOOL -> TensorDataType.BOOLEAN
    else -> TensorDataType.UNKNOWN
}
