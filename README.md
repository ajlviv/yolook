# YOLook

On-device real-time object detection for Android. YOLook runs YOLOv8 through TensorFlow Lite directly on the phone, tracks detected objects with a  multi-object tracker, and stays fully offline.

## Features

- **Live detection** - Camera preview with an overlay that draws bounding boxes, class labels, and stable track IDs.
- **ByteTrack multi-object tracking** - Pure-Kotlin tracker (Kalman predict + Hungarian matching on IoU). Low-confidence detections keep matching across brief occlusions, so objects keep their ID.
- **Tunable inference** - Confidence and IoU thresholds, max objects per frame, inference FPS cap, GPU-delegate on/off, and a per-class filter. All settings persist to Jetpack DataStore and take effect live.
- **Detection history** - In-memory rolling list of the last 500 detections with label, track ID, confidence, and timestamp.
- **Snapshots** - Save the current preview frame as a JPEG to the gallery (`Pictures/YOLO`) via MediaStore.
- **Hardware acceleration** - GPU delegate with graceful fallback to NNAPI, then CPU.
- **Driver mode** - A dash-cam style HUD with **icon-only object markers** (no bounding boxes), a **top-right traffic-light panel** that recovers each lane's red/yellow/green signal from the image, **sign recognition** drawn above the lights, alerts when **people are on the road**, optional **audio warnings** (toggle in Settings), and an option to **hide the camera preview** entirely and render only icons/HUD.

## Screens

- **Live** - full-screen camera with detection overlay plus a snapshot button.
- **History** - recent detections listed newest-first, with a clear action.
- **Settings** - sliders/toggles for every inference parameter and a reset-to-defaults action.

## Model

Build model and add it to the `app/src/main/assets` (see `ModelAssets.FILE_NAME`)

```python
from ultralytics import YOLO

model = YOLO('yolov8n.pt')
model.export(format='tflite', imgsz=640, int8=False)
```

See `app/src/main/assets/README.md` for full export notes.

## License

See the repository for license details.