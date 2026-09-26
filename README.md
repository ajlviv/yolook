# YOLook

On-device real-time object detection for Android. YOLook runs a static YOLO model through TensorFlow Lite directly on the phone, tracks detected objects with a multi-object tracker, and stays fully offline.

## Features

- **Live detection** - Camera preview with an overlay that draws bounding boxes, class labels, and stable track IDs.
- **ByteTrack multi-object tracking** - Pure-Kotlin tracker (Kalman predict + Hungarian matching on IoU). Low-confidence detections keep matching across brief occlusions, so objects keep their ID.
- **Tunable inference** - Confidence and IoU thresholds, max objects per frame, inference FPS cap, GPU-delegate on/off, and a per-class filter. All settings persist to Jetpack DataStore and take effect live.
- **Detection history** - In-memory rolling list of the last 500 detections with label, track ID, confidence, and timestamp.
- **Snapshots** - Save the current preview frame as a JPEG to the gallery (`Pictures/YOLO`) via MediaStore.
- **Hardware acceleration** - GPU delegate when enabled and supported, otherwise CPU inference.

## Screens

- **Live** - full-screen camera with detection overlay plus a snapshot button.
- **History** - recent detections listed newest-first, with a clear action.
- **Settings** - sliders/toggles for every inference parameter and a reset-to-defaults action.

## Model

Build the model and add it to `app/src/main/assets` as `yolo11n.tflite` (see `ModelAssets.FILE_NAME`):

```python
from ultralytics import YOLO

model = YOLO('yolo11n.pt')
model.export(format='tflite', imgsz=640, int8=False)
```

The app validates the model tensor contract before inference. The current profile is static COCO detection. YOLOE runtime text/visual prompts, prompt-free vocabularies, and segmentation outputs require a separate profile and detector implementation; a YOLOE artifact should not be added under the current filename without validating its input/output tensors.

See `app/src/main/assets/README.md` for export and contract notes.

## License

See the repository for license details.