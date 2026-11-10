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
