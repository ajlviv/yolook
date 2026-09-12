# Assets

Place the YOLOv8m TFLite model file here **before building**:

```
app/src/main/assets/yolov8m.tflite
```

## Export from Ultralytics Python

```python
from ultralytics import YOLO

model = YOLO('yolov8m.pt')
model.export(format='tflite', imgsz=640, int8=False)
```

The exported file will be at:
`runs/detect/train/weights/best_saved_model/best_float32.tflite`

Rename it to `yolov8m.tflite` and copy it here.

## Driver-mode sounds

Driver-mode audio warnings are bundled under `app/src/main/assets/sounds/`:
- `warning_danger.wav` - red light / person on the road
- `warning_caution.wav` - amber light
- `sign_notice.wav` - speed-limit sign

These are played (best-effort) via `android.media.MediaPlayer` when sound warnings are
enabled in Settings. To change a sound, drop a replacement WAV with the same name.

> Note: the stock COCO model only detects a plain "traffic light" box and "stop sign".
> It does not classify the lamp color or detect speed-limit signs. The lamp color is
> recovered from pixel color sampling; speed-limit recognition is an extension point
> (`SpeedLimitSignRecognizer`) that is empty until a companion model is supplied.

## Expected model shape
- Input:  `[1, 640, 640, 3]` — RGB float32, values in [0, 1]
- Output: `[1, 84, 8400]` — 84 = 4 bbox coords + 80 COCO class scores
