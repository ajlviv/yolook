Plan: Driver Mode for YOLook (YOLO detector)

1\. Current system (what I verified)

\- Stack: Kotlin 2.0.21 / Jetpack (android.\* APIs) on Android SDK 35, CamerAX 1.3.4, TensorFlow Lite 2.16.1 (YOLOv8m, output \[1, 84, 8400]), ByteTrack tracker, Jetpack DataStore for settings. No audio dependency yet.

\- Pipeline: CameraManager.processFrame → TfliteDetector.detect (returns List<Detection> with classId from COCO) → ByteTracker.update → detectionFlow. Filtered view modes additionally emit a frame copy via frameFlow.

\- View modes: data/ViewMode.kt enum NORMAL | BLACK\_AND\_WHITE | INVERT | HEATMAP, selected via a Spinner in SettingsFragment; LiveFragment.applyViewMode toggles logic. Enum ordinal order must match strings.xml view\_modes array.

\- Overlay: BoundingBoxOverlay (a View) draws colored boxes + labels from detectionFlow.

\- Settings: InferenceSettings data class ↔ SettingsRepository (DataStore keys) ↔ MainViewModel.setXxx() delegates ↔ SettingsFragment.

2\. Requirements (driver mode)

1\. Traffic light recognition — detect one-or-more lights (per lane) and show the current signal.

2\. Top-right HUD — draw recognized traffic lights and their signal.

3\. Sign recognition — draw recognized signs above the traffic-light HUD.

4\. Person-on-road detection — warn on people in the road.

5\. Audio warnings (toggle in settings): yellow/red traffic light, person on road, speed-limit sign.

6\. Icon-only rendering — no bounding boxes in driver mode; draw a compact icon (person, car, …) at the top of each object.

7\. Hide-camera option — fully disable the camera preview in driver mode and render only icons/HUD.

3\. Key design decisions \& tradeoffs

3a. Traffic-light color classification ⚠️ core challenge

The COCO model only outputs class 9 = "traffic light" (a bounding box). It does not tell us red/yellow/green.

Option	Approach

A. Color sampling (recommended, v1)	Inside each traffic-light bbox, sample pixels and classify the lit lamp via HSV thresholds (red/yellow/green/off). Uses existing Bitmap.getPixels (ARGB IntArray) — exactly what BitmapUtils already does.

B. Small crop classifier	Crop bbox → run a tiny TFLite classifier (4 classes).

C. Dedicated TL model	Train YOLO on LISA/TLBoost with red/yellow/green labels.

Decision: Build A behind an abstraction TrafficLightColorEstimator with a clean interface so it can later be swapped for B/C. Stabilize the signal with temporal smoothing (majority vote over \~5 frames + debounce) keyed on trackId to avoid flicker.

Multiple lights / lanes: Each traffic light is a tracked Detection with a trackId. We keep per-track signal state and group them into lanes by clustering the normalized x-center of lights (e.g., quantize to 2–3 x-bands). Each lane's light is rendered as a colored dot in the HUD.

3b. Speed-limit sign recognition ⚠️ model gap

COCO has stop sign (11) but no "speed limit" class. To recognize speed-limit signs and their value we must add capability:

\- Recommended pathway (model asset): train a small YOLOv8 model on speed-limit signs; classes = {speedlimit} (and optionally per-value classes 20/30/…/120). Export to TFLite exactly like the current assets/README.md workflow and load as a second TfliteDetector-like object. A cheap alternative is a single-class "speed limit" detector + a digit OCR/classifier on the cropped sign.

\- Heuristic fallback: detect the red circular sign via color/geometry and render a generic "SPEED LIMIT" icon without the number.

Decision: Define a SignRecognizer interface with two implementations: a stub SignRecognizer (returns generic SPEED\_LIMIT/STOP from COCO class 11 + heuristic), and a SpeedLimitModelSignRecognizer that runs the optional companion TFLite model when present. The plan ships the heuristic path first and defers model training to a follow-up milestone (clearly flagged).

3c. Person on road

Use existing person (class 0). Define "on road" as: person bbox center in the lower/middle band of the frame (configurable fraction, default lower 60%), optionally restricted to near detected vehicles. Simpler default: any person detection triggers the danger warning (config toggle). Keep it a pure predicate function for testability.

3d. Audio ⚠️ new capability

Jetpack ships android.media (MediaPlayer / audio renderer) and emoji via android.droidfont.AndroidGlyph, but these are not yet imported anywhere in this codebase. First implementation step must be a small spike to confirm the exact android.media.MediaPlayer API, whether android.media requires enabling in the SDK/manifest, and to add the audio/media engine if needed. Plan:

\- Bundle short WAV assets (warning, danger, sign-ding) under app/src/main/assets/sounds/.

\- WarningSoundManager: plays via Jetpack MediaPlayer, with enable/disable + volume settings and a debounce/cooldown per event type (e.g., ≥1.5 s) so it doesn't replay every video frame.

3e. Icon rendering (no boxes)

New overlay view (DriverObjectIconsOverlay) that, for each detection, draws no border — only a small icon glyph centered horizontally above the object's bbox top edge. Mapping class → glyph (person 🚶, car 🚗, motorcycle 🏍, bus 🚌, truck 🚚, traffic light 🚦, stop sign ⛔, speed limit/circle). Prefer android.droidfont.AndroidGlyph (emoji) — validated in the same audio/UI spike; fallback is a small hand-drawn vector via Canvas primitives.

3f. Camera off option

Add driverModeHideCamera: Boolean (default false). When on and mode is DRIVER, LiveFragment hides previewView (and imageFilterPreview), leaving black background + DriverObjectIconsOverlay + top-right HUD. Inference continues; the frame copy still flows to the pipeline/estimators.

4\. New settings (persisted)

Add to InferenceSettings:

\- driverModeHideCamera: Boolean = false

\- soundEnabled: Boolean = true

\- soundVolume: Float = 1f (0–1)

Add to SettingsRepository.Keys + toSettings()/set\*:

\- "driver\_hide\_camera" (booleanPreferencesKey), "sound\_enabled" (boolean), "sound\_volume" (float).

Add MainViewModel delegates: setDriverModeHideCamera, setSoundEnabled, setSoundVolume.

5\. Changes by file

New files

\- data/DriverTypes.kt — enum TrafficLightSignal { RED, YELLOW, GREEN, OFF, UNKNOWN }, enum SignType { STOP, SPEED\_LIMIT, UNKNOWN }, data class DriverScene(trafficLights: List<TrafficLightHud>, signs: List<SignHud>, peopleOnRoad: Int, warnings: Set<WarningType>).

\- inference/TrafficLightColorEstimator.kt — interface + HsvTrafficLightColorEstimator operating on the frame Bitmap + traffic-light detections (pure/unit-testable).

\- inference/SignalStabilizer.kt — per-trackId smoothing (majority vote + debounce); pure logic, unit-testable.

\- inference/SignRecognizer.kt — interface + heuristic implementation (COCO stop sign; circular-red heuristic); optional companion-model impl stub.

\- inference/LaneClusterer.kt — cluster traffic-light x-centers into lanes; pure logic.

\- audio/WarningSoundManager.kt — playback + cooldowns + volume, honoring soundEnabled.

\- ui/overlay/DriverObjectIconsOverlay.kt — icon-only rendering (no borders).

\- ui/overlay/DriverHudOverlay.kt — top-right panel: traffic-light signal dots/label; signs drawn above it.

\- Assets: app/src/main/assets/sounds/{warning,danger,sign}.wav.

Modified files

\- data/ViewMode.kt — add DRIVER (keep ordinal position last / append so existing persisted values stay valid).

\- data/InferenceSettings.kt — new fields.

\- data/SettingsRepository.kt — keys + read/write.

\- ui/MainViewModel.kt — expose driverSceneFlow, wire DriverSceneBuilder, delegates for new settings.

\- ui/screens/LiveFragment.kt — on DRIVER mode: hide boxes overlay, show icon overlay + HUD, apply camera-off toggle; collect driverSceneFlow.

\- ui/screens/SettingsFragment.kt — add switches for sound + hide-camera; driver entry already appears in the extended view\_modes spinner.

\- res/values/strings.xml — add strings and extend view\_modes array (e.g. append @string/view\_mode\_driver at the end), plus settings\_sound, settings\_hide\_camera, sound option labels.

\- res/layout/fragment\_live.xml — add the two overlay views + HUD container gated to driver mode.

\- res/layout/fragment\_settings.xml — add sound + hide-camera rows.

\- AndroidManifest.xml — add any android.media/feature declarations the audio spike reveals.

6\. Driver pipeline flow (in MainViewModel/LiveFragment)

detectionFlow (tracked ClassId detections)

&#x20;  ├─ DriverObjectIconsOverlay   (icon per object, no boxes)

&#x20;  ├─ traffic-light class(9) → TrafficLightColorEstimator(frame) → SignalStabilizer(per-track) → LaneClusterer

&#x20;  ├─ person class(0) predicate → peopleOnRoad

&#x20;  ├─ SignRecognizer → signs

&#x20;  └─ DriverScene → DriverHudOverlay + WarningSoundManager(soundEnabled, volume)

Driver scene construction reuses the emitted inference frame (frameFlow/detectionFlow) so it runs only in driver mode. Audio operates on a fixed UI cadence (not per video frame) with cooldowns.

7\. Testing plan

Follow the existing pattern (src/test/..., JUnit 4 / Kotlin):

\- TrafficLightColorEstimatorTest — feed synthetic ARGB int arrays (red/yellow/green/dark) → assert signal.

\- SignalStabilizerTest — flapping input → stable output; debounce timing.

\- LaneClustererTest — two/three lights cluster into expected lanes.

\- PersonOnRoadTest — predicate boundaries.

\- InferenceSettings\*Test — new-key serialization round-trip (viewport of existing repository logic).

\- Manual/Espresso: switch to driver mode, verify HUD top-right + icons + no boxes; toggle sound off; toggle hide-camera.

\- Run: .\\gradlew.bat test (plus lint/compileDebugAndroidKotlin as configured).

8\. Risks \& mitigations

\- Audio API unverified → step 0 spike; abstract behind WarningSoundManager.

\- Traffic-light color robustness → HSV thresholds + temporal smoothing + tunable constants; fallback signal UNKNOWN.

\- Speed-limit model gap → ship heuristic/generic sign first; companion-model milestone is optional and clearly separated.

\- Settings migration → append DRIVER/keys only (no reordering), preserving existing persisted enums.

\- Perf → color sampling runs only in driver mode, downsampled to the bbox region; icon overlay is cheap vector/glyph drawing. Backround-thread work is confined to the estimator (like the heatmap baker pattern).

9\. Implementation order (milestones)

1\. Spike: confirm android.media playback + android.droidfont.AndroidGlyph availability; enable required engine/manifest bits; add WAV assets.

2\. Data/model plumbing: add DRIVER view mode (strings + enum), new settings (InferenceSettings/Repository/ViewModel), Settings UI rows.

3\. Pure logic: TrafficLightColorEstimator, SignalStabilizer, LaneClusterer, SignRecognizer, person predicate + unit tests.

4\. Rendering: DriverObjectIconsOverlay + DriverHudOverlay; wire into LiveFragment layout (+hide-camera toggle).

5\. DriverScene + driverSceneFlow in MainViewModel; Demo/Espresso wiring.

6\. Audio: WarningSoundManager, cooldowns, tie to scene warnings.

7\. Polish + full test run + README (assets/README.md, feature list, settings docs).

This plan keeps new infrastructure behind abstractions, reuses the existing settings/pipeline/overlay patterns, and isolates the two biggest unknowns (audio + traffic-light color) behind interfaces with unit tests. It introduces no changes yet — I've only inspected the code. When you're happy with this direction, switch me to act mode (or tell me to start) and I'll implement milestone by milestone, beginning with the audio/glyph spike and the DRIVER view-mode plumbing.

