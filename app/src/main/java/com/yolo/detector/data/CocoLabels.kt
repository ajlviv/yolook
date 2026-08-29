package com.yolo.detector.data

/**
 * All 80 COCO class names indexed by class ID (0–79).
 *
 * Order matches the standard COCO dataset label ordering used by YOLOv8.
 */
val COCO_LABELS: List<String> = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane",
    "bus", "train", "truck", "boat", "traffic light",
    "fire hydrant", "stop sign", "parking meter", "bench", "bird",
    "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat",
    "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
    "wine glass", "cup", "fork", "knife", "spoon",
    "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut",
    "cake", "chair", "couch", "potted plant", "bed",
    "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven",
    "toaster", "sink", "refrigerator", "book", "clock",
    "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
)

/**
 * COCO class IDs that represent vehicles relevant to traffic/surveillance monitoring.
 * - 2: car
 * - 3: motorcycle
 * - 5: bus
 * - 7: truck
 */
val VEHICLE_CLASS_IDS: Set<Int> = setOf(2, 3, 5, 7)

/** Returns the human-readable COCO label for a class ID, or "unknown" if out of range. */
fun labelFor(classId: Int): String = COCO_LABELS.getOrElse(classId) { "unknown" }
