# FormCheck v2 Changelog

One entry per phase of the v2 plan. Each entry lists what was done, how it was verified,
and what could not be completed and why.

## Phase 0: Audit

**Done**
- Wrote `docs/AUDIT.md`: every score, angle and feedback string in v1 classified as
  measured / assumed / hardcoded; media storage; sampling rate; pose model; handedness;
  change list mapped to phases.

**Key findings**
- `GuideHandDetector` returns a placeholder score of 85 ("Guide hand looks neutral") when it
  cannot measure anything, and the overall score averages it in.
- Empty input produces 0° / score 0 instead of "Not measured".
- Results screen bars for elbow extension and knee bend are `angle / 180 × 100`, which is not a score.
- Picker `content://` URIs are stored, so uploaded videos disappear after restart. Landmarks
  are never saved.
- 10 fps sampling, ML Kit 2D landmarks, right hand assumed everywhere, no view detection,
  dropped frames desync the overlay.

**Verified**
- Baseline `assembleDebug` of the v1 code succeeded before any change.

**Not completed**
- Nothing outstanding for this phase.

## Phase 2: Accuracy foundation

**Done**
- Toolchain: Gradle wrapper scripts added (Gradle 8.14.3); AGP 8.5 → 8.13.2, Kotlin 2.0 → 2.3.21,
  KSP 2.3.13, compileSdk 34 → 36. Versions were checked against Google Maven and Maven Central
  on 2026-11-09 (see comments in `gradle/libs.versions.toml`).
- New `tracking` package (pure Kotlin, JVM-testable):
  - `PoseFrame` with MediaPipe's 33 landmarks: image pixels for drawing, world meters for angles,
    per-landmark visibility. `TrackedFrame` keeps every frame on the timeline (no dropped frames).
  - `OneEuroFilter` / `LandmarkSmoother` per coordinate, driven by real timestamps; filters reset
    across detection gaps instead of bridging them.
  - Visibility gating at 0.6 (`TrackingConfig.VISIBILITY_THRESHOLD`). `PoseMath` returns null for any
    angle whose landmarks are gated, so callers must report "Not measured".
  - `ViewClassifier` (side / front / angled from shoulder-width-to-torso ratio and shoulder depth
    difference) and `MetricValidity` encoding the spec 3b table.
  - `ScaleCalibration` (ball diameter → user height for the shooter plane; rim width for the rim plane).
  - `ProjectileFitter` (RANSAC parabola in time), `Homography` (normalised DLT), `BallKalmanTracker`,
    `ClassicalBallRimDetector`, and the `BallRimDetector` interface (wired up in phase 5).
- Android pipeline: `VideoFrameDecoder` (MediaExtractor + MediaCodec, native frame rate, real
  presentation timestamps, slow-motion capture-rate correction), `PoseEngine` (MediaPipe Pose
  Landmarker heavy, VIDEO mode, GPU delegate with CPU fallback, multi-person pick), `ClipTracker`.
- `FormShotAnalyzer`: every metric gated by view, visibility, ball/rim and scale; unmeasurable
  metrics carry a one-line reason. Shooting hand comes from the profile, is auto-detected
  (wrist under the ball at the set point), and a mismatch produces a warning. No right-hand default
  except as a last resort with a warning.
- `Metric` model (value, unit, confidence, frame evidence, overlay hint, or Not measured + reason) and
  `ScoringConfig` with a source note and source kind for every range.
- Models are fetched at build time from Google's MediaPipe bucket by `:app:downloadModels` into a
  gitignored assets folder.

**Verified**
- `assembleDebug` succeeds. 28 JVM unit tests pass: 3D/2D joint angles, synthetic postures with known
  knee/elbow/trunk angles, visibility gating, One Euro behaviour (noise, lag, variable dt), view
  classification, RANSAC launch angle with outliers (52° recovered within 0.7°), homography (exact
  with 4 points, < 2 px with 6 noisy points), Kalman outlier rejection, classical ball detection and
  static-object rejection, circle fit, jump flight time (h = g·t²/8), scoring maths, and an
  end-to-end synthetic side-view shot (knee 120°, set elbow 75°, release elbow 170° recovered within
  0.6°).

**Not completed / notes**
- The new pipeline is not wired into the screens yet. The v1 ML Kit path stays in place for this
  commit so the build stays green. Phase 3 switches the app over and removes ML Kit.
- Toolchain ceiling: AndroidX Core 1.19, Navigation 2.10, Hilt-AndroidX 1.4 and Dagger Hilt 2.59+
  require compileSdk 37 and/or AGP 9.x. They stay on older versions (Core 1.13.1, Navigation 2.8.1,
  androidx.hilt 1.3.0, Hilt 2.57.2) until a separate AGP 9 migration, which changes the Kotlin
  plugin setup and deserves its own change.
- `targetSdk` stays 34 to avoid unrelated behaviour changes (edge-to-edge enforcement at 35).

## Phase 3: Saved sessions and Library

**Done**
- The app now runs on the v2 pipeline. The v1 analysis classes, ML Kit, and the placeholder-score
  UI (PhaseCard, SkeletonOverlay, v1 Analysis/Results screens) are removed.
- Media is copied into `filesDir/sessions/{id}/source.mp4` on import and on record (recordings go
  to cache, then are moved in). Picker URIs are never the only copy. The folder also holds
  `params.json`, `track.bin.gz` (per-frame landmarks and ball, gzipped binary `TrackCodec`),
  `thumb.jpg` and `result.json`.
- Room v2: `sessions` (type, status, file paths, overall score with N of M, makes/attempts,
  size), `metrics` (every metric with value, unit, confidence, score, frame evidence, reason),
  `shots`. `MIGRATION_1_2` keeps v1 rows as LEGACY sessions: date and URI survive, the v1
  numbers go to `legacyJson`, and no v1 number is shown as a score (the audit found placeholders).
- `AnalysisWorker`: foreground WorkManager job (dataSync type) with progress notification and
  progress in the UI. Leaving the screen does not cancel it.
- Screens: onboarding (shooting hand and height), Home with modes, mode screen with filming
  guidance, capture at the highest supported frame rate up to 60 fps (high-speed session for the jump
  test when supported), person picker when several people are in frame, analysis progress,
  results list, Library (thumbnails, filter by type, rename, delete with confirmation, storage
  used), Playback that draws skeleton, ball trail, fitted arc and angle labels live from saved
  landmarks (each toggleable), 0.25×/0.5×/1× speed, frame stepping, and evidence frame jump.
- Annotated MP4 export with Media3 Transformer (`CanvasOverlay`), shared through FileProvider only
  when the user taps Share.
- `INTERNET` is stripped from the merged manifest with `tools:node="remove"`.
- Photo upload is removed (picker is video-only). A still frame cannot show dip, release or
  follow-through, so every v2 form metric would be "Not measured".

**Verified**
- `assembleDebug` succeeds. 31 unit tests pass, including:
  - `MigrationTest` (Robolectric): builds a real v1 database from the committed `1.json`,
    inserts v1 rows, opens it with Room v2 and the migration (Room validates the schema on open),
    checks legacy rows, the absence of v1 scores, and cascade deletes on the new tables.
  - `TrackCodecTest`: round trip of 50 frames with gaps, exact image coordinates, world
    coordinates within 0.6 mm, the rim, the time scale, and the result JSON.
- Merged debug manifest checked: no `android.permission.INTERNET`.

**Not completed / notes**
- Not run on a phone or emulator: this machine has no emulator system image. Camera capture,
  MediaCodec decoding, the GPU delegate, WorkManager foreground execution and Transformer export
  compile but were not exercised at runtime here.
- The export overlay assumes Transformer hands the overlay upright frames. Rotated source files
  need a check on a device.

## Phase 4: Score dashboard

**Done**
- `ScoreDashboard` (reusable for form, shootaround and dribbling): large overall ring with the
  percentage, its band, and "from N of M metrics" (unmeasured metrics are left out, never
  guessed); a "biggest fix" card for the lowest scoring measured metric with one specific tip
  (`FixTips`); one row per metric with name, value and unit (user's units), percentage bar,
  three-dot confidence indicator, and an info dialog giving the description, target range,
  source kind (physics / published data / coaching convention / app heuristic), source text
  and scoring rule.
- Bands: green 80–100, yellow 60–79, red below 60, gray for Not measured, plus a separate "no
  target" state for measured metrics without a defensible range. Every state has its own icon
  and always shows the number, so colour is never the only signal. Rows have merged content
  descriptions for TalkBack.
- Tapping a row or the biggest-fix card opens Playback at the evidence key frame with the angle
  drawn on the overlay and buttons for the evidence start, key and end frames.
- Results screen: view, frame rate, hand and scale source; a "what limited this analysis" card;
  shot selector for multi-shot sessions; session-level metrics (consistency) under the shot dashboard.

**Verified**
- `assembleDebug` succeeds; 34 unit tests pass (new `BandTest` pins the 80/60 cutoffs, gray for Not
  measured, and distinct icons per band).

**Not completed / notes**
- Visual layout was not checked on a device (no emulator here).

## Phase 5: Ball and rim detection, release and arc metrics, consistency

**Done**
- `BallRimDetector` with a learned implementation (MediaPipe Object Detector: EfficientDet-Lite0
  "sports ball", or a drop-in `ball_rim.tflite` with ball and rim classes) and a classical no-ML
  implementation (colour segmentation, circle fit, motion gating), with automatic fallback when
  the model is missing or its score is low. `BallKalmanTracker` rejects physically impossible jumps.
- Rim: two user taps on the rim edges (most accurate), else the learned rim class, else the
  classical detector. The rim is median-filtered over the first samples and re-checked twice a
  second to flag camera movement.
- Ball-based shot events in `ShotEventDetector`: release is the first frame the ball surface is
  more than half a diameter from the shooting hand and keeps moving away and up, from above the
  shoulder (this replaces the v1 wrist-extension guess). Dip is the lowest held ball position.
  Set point is the most bent shooting elbow between dip and release.
- Release angle, arc apex and entry angle come from a RANSAC projectile fit. Confidence falls
  with the RMS residual relative to ball radius and with the outlier share; a noisy fit
  (confidence < 0.15) is reported as Not measured with the residual in the reason. Also:
  dip-to-release timing, follow-through hold (with "clip ended while still holding" handling),
  balance drift, landing drift.
- Consistency score (standard deviation of release angle, release height and timing; needs 3+
  shots) is computed for multi-shot form clips and feeds shootaround in phase 6.
- `docs/MODELS.md`: model sources, licenses (verified as described there), training data,
  model-card limits that affect the app, and why no custom basketball model ships.

**Verified**
- 35 unit tests pass. New: entry angle at a known rim matches projectile physics within 1°.
  Earlier tests already cover release-frame detection, release angle, RANSAC with outliers,
  Kalman gating and classical detection.
- Pose model license: read directly from the official model card PDF (Apache 2.0). EfficientDet-Lite0:
  the MediaPipe page states none, the TensorFlow Kaggle listing states Apache 2.0. MediaPipe
  library: Apache 2.0 repository license.

**Not completed / deviations**
- No custom ball+rim model was trained or shipped (no permissively licensed one found;
  Ultralytics YOLO is AGPL-3.0; training and validating one was not possible here). The learned
  path uses COCO "sports ball", and the rim comes from user taps or the classical detector.
- Scale priority deviates from the spec's literal order (rim → ball → height). With one camera,
  the rim's scale is only valid at the rim's depth, so shooter-plane distances use ball → height,
  and rim-plane quantities use the rim. Using the 10 ft rim height would also need the floor
  under the rim, which is usually out of frame.
- Detection accuracy on real footage is unmeasured until validation clips exist (phase 10).
