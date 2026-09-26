# Assets

Place the current static YOLO TFLite model here **before building**:

```
app/src/main/assets/yolo11n.tflite
```

## Export from Ultralytics Python

```python
from ultralytics import YOLO

model = YOLO('yolo11n.pt')
exported = model.export(format='tflite', imgsz=640, int8=False)
print(exported)
```

Use the path returned by `model.export(...)`, rename the exported file to `yolo11n.tflite`, and copy it here.

## Expected model contract

- Input: `[1, 3, 640, 640]` — RGB float32, values in `[0, 1]`
- Output: `[1, 84, 8400]` — 4 bbox coordinates + 80 COCO class scores
- Task: detection only
- Prompt mode: none

The app validates these tensor counts, shapes, and data types before inference. This fixed COCO 640x640 / `[1, 84, 8400]` detection contract is the only one the current app accepts: a non-COCO static vocabulary can be described by `ModelProfile`, but `validateForCurrentUi` rejects it, so it cannot be registered or loaded today. Runtime text/visual prompts and segmentation outputs are not accepted at all.
