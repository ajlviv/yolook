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

## Expected model shape
- Input:  `[1, 640, 640, 3]` — RGB float32, values in [0, 1]
- Output: `[1, 84, 8400]` — 84 = 4 bbox coords + 80 COCO class scores
