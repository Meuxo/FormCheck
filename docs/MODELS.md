# On-device models: sources and licenses

FormCheck runs every model on the phone. No model is committed to this repository. The Gradle
task `:app:downloadModels` (run automatically before every build) fetches them from Google's
official MediaPipe model bucket into `app/src/main/assets/models/`, which is gitignored.
They are still packaged inside the APK, so the licenses below apply to anyone who distributes
a build.

Checked on 2026-11-09.

| File | What it does | Source | License | How verified |
|---|---|---|---|---|
| `pose_landmarker_heavy.task` | Body pose: 33 image landmarks + 3D world landmarks (meters) | `storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/` (linked from the MediaPipe Pose Landmarker guide) | **Apache License 2.0** | Stated in the official model card "BlazePose GHUM 3D" (`mediapipe-assets/Model Card BlazePose GHUM 3D.pdf`, "Licensed under Apache License, Version 2.0") |
| `efficientdet_lite0.tflite` (int8) | Generic object detector; FormCheck uses only its COCO **sports ball** class | `storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/` (linked from the MediaPipe Object Detector guide) | **Apache License 2.0** | The MediaPipe guide states no license for this file. The same EfficientDet-Lite0 detection model is published by TensorFlow on Kaggle (`tensorflow/efficientdet`, instance `lite0-detection`) under Apache 2.0, and the EfficientDet reference code (`google/automl`) is Apache 2.0. Treat this as the best available evidence, not as a statement on the exact int8 file. |
| MediaPipe Tasks library (`com.google.mediapipe:tasks-vision`) | Runs both models | Google Maven | **Apache License 2.0** | `google-ai-edge/mediapipe` repository LICENSE |

Training data, as stated by the sources:
- Pose: the model card describes about 30K consented images captured on smartphone back cameras
  through an AR app, plus augmentations. No public dataset is named.
- EfficientDet-Lite0: COCO 2017. COCO annotations are CC BY 4.0 and the images carry their own
  Flickr licenses. Neither applies to the trained weights, but they are noted for completeness.

## Limits stated by the pose model card that affect FormCheck

The model card lists these as out of scope or limitations. The app works within them where it
can and says so where it cannot:

- **Multiple people.** The model tracks one person reliably. When several people are in frame,
  FormCheck asks the user to tap the shooter and follows the nearest pose, but crowded clips
  remain less reliable.
- **People farther than about 4 m (14 ft) from the camera.** Shootaround filming often puts the
  shooter farther away than this. Pose-based numbers in shootaround (shot location from the feet,
  per-shot form) carry lower confidence for that reason. Makes, misses and arc come from the
  ball and rim, not the pose model.
- **Metric-accurate depth is out of scope.** FormCheck uses world landmarks for joint *angles*,
  not for absolute depth. Real distances come from the ball, the rim or the user's height (see
  `ScaleCalibration`), never from the model's z coordinate.
- The model is "not intended for human life-critical decisions". FormCheck is a training aid
  and makes no medical or injury claims.

## Ball and rim detector (spec 3d)

`BallRimDetector` has two implementations plus a fallback combinator:

1. **Learned** (`LearnedBallRimDetector`), through MediaPipe's Object Detector task:
   - If `app/src/main/assets/models/ball_rim.tflite` exists, it is used, with labels `ball` and
     `rim` (or `basketball` / `hoop`). No such model ships with the app (see below).
   - Otherwise EfficientDet-Lite0's COCO `sports ball` class detects the ball. COCO has no rim
     class, so the rim comes from the user's two taps on the rim edges, or from the classical detector.
2. **Classical, no ML** (`ClassicalBallRimDetector`): HSV colour segmentation for basketball
   orange, connected components, shape tests (aspect, ellipse fill, least-squares circle fit
   residual) and motion gating against the previous frame's colour mask. The rim is a wide,
   thin red/orange component above the shooter's waist, sampled twice a second and
   median-filtered (the camera is assumed static).

The learned result is used when its best score is at least 0.35. Below that, classical candidates
are added. A constant-velocity Kalman filter with chi-square gating and a physical speed limit
(20 m/s, or 4 frame diagonals per second without a scale) rejects impossible jumps and coasts
through short gaps.

### Why no custom basketball model ships

Spec 3d allows training one, but nothing suitable could be shipped honestly:
- No permissively licensed, pretrained **ball + rim** TFLite model could be found from a source
  that states its license.
- The most common public basketball detectors are Ultralytics YOLO models. Ultralytics code and
  weights are **AGPL-3.0**, which is incompatible with distributing this MIT-licensed app.
- Training a model needs a GPU and a dataset whose license allows redistribution of derived
  weights. That could not be done and validated inside this change.

**How to add one later.** Train an EfficientDet-Lite model with
[MediaPipe Model Maker](https://ai.google.dev/edge/mediapipe/solutions/customization/object_detector)
(Apache 2.0) on a dataset whose license allows it (for example a CC BY 4.0 basketball set,
crediting it here). Use labels `ball` and `rim`, export to TFLite, and drop the file in as
`assets/models/ball_rim.tflite`. No code change is needed. Record the dataset, its license and the
training settings in this file.
