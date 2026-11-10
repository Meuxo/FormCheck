# FormCheck v1 Audit

Audited commit: `214b4c6` (Initial commit). Scope: every Kotlin source file under
`app/src/main/java/com/meuxo/formcheck/` (40 files, ~3,950 lines), the Gradle build, the
manifest and the Room schema `app/schemas/.../1.json`.

Legend for the "Status" column:

- **Measured**: computed from the user's footage with a defensible method.
- **Assumed**: computed from footage, but relies on an unstated assumption (camera angle,
  right-handed shooter, pixel thresholds) that is often false.
- **Hardcoded**: a constant, placeholder, or invented mapping shown to the user as if it
  were a measurement.

---

## 1. Every place a score, angle, or feedback string is produced

### 1.1 `analysis/AngleEvaluator.kt`

| Output | How it is produced | Status | Problem |
|---|---|---|---|
| `elbowAngleDeg` | 2D image angle shoulder→elbow→wrist on the **right** arm at the frame with the largest right-elbow angle in the RELEASE/LIFT/FOLLOW_THROUGH window (or the top quartile of frames by right-wrist height) | Assumed | Right hand hardcoded. "Release" is the most-extended frame, not the real release. 2D angle is only valid in a side view, but no view check. |
| `elbowAngleScore` | 100 inside 150–180°, minus 2 points per degree outside | Hardcoded | Band and slope were hand-tuned ("widened to match what ML Kit can detect"). No source. |
| `elbowFlarePct` | `abs(rightElbow.x - rightWrist.x) / shoulderWidth` at the release frame | Assumed | Only meaningful in a front view; in a side view shoulder width collapses toward 0 and the ratio explodes. Right hand hardcoded. |
| `elbowFlareScore` | Piecewise curve 20/35/55 % → 100/80/55, floored at 25 | Hardcoded | Invented curve. Floors at 25, so a terrible measurement can never score below 25. |
| `kneeBendDeg` | Mean of left and right 2D knee angles at the deepest frame in the LOAD window | Assumed | 2D angle, no view check, no visibility gating (an occluded far leg is averaged in). |
| `kneeBendScore` | 100 inside 100–160°, minus 2 points per degree | Hardcoded | Invented band (the comment says the original 130–150° "was way too narrow"). |
| `setPointScore` / `setPointBelowPctBodyHeight` | Right wrist Y vs. an estimated forehead Y | Assumed | `foreheadY = eyeMidY - (noseY - eyeMidY) * 1.5` is a guess. "8 points per 5 %" is invented. |
| Empty input | `Result(0f, 0, 0f, 0, 0f, 0, 0, 0f)` | **Hardcoded** | No pose shows as **0° and score 0**, which looks like a real (terrible) measurement. |

### 1.2 `analysis/BalanceScorer.kt`

| Output | How | Status | Problem |
|---|---|---|---|
| `offsetPctShoulderWidth` | Hip-midpoint vs. ankle-midpoint horizontal offset on **one** frame (median LIFT/RELEASE frame, or the middle of the clip) | Assumed | One frame, not drift through the shot. Normalized by 2D shoulder width, so it breaks in side view. |
| `score` | Piecewise 18/30/50 % curve, floored at 30 | Hardcoded | Invented. Floors at 30. |
| Empty input | `Result(0, 0f)` | **Hardcoded** | Shows "0" instead of "Not measured". |

### 1.3 `analysis/GuideHandDetector.kt`

| Output | How | Status | Problem |
|---|---|---|---|
| `score`, `verdict` | Ratio of left-wrist to right-wrist displacement between the two halves of the release window | Assumed | Left = guide hand hardcoded. Ratio buckets (0.35/0.55/0.80 → 100/85/70/50) invented. |
| Fewer than 3 frames, or empty windows | **`Result(85, "Guide hand looks neutral")`** | **Hardcoded** | A placeholder score of 85 and a made-up verdict appear whenever the metric could not be computed. This is the clearest rule 1 violation. |

### 1.4 `analysis/OverallScorer.kt`

Fixed weights (0.20/0.20/0.15/0.15/0.15/0.15) over all six metrics, **including** the
placeholder 85 and the zeros from empty input. Status: **Hardcoded weights, unmeasured
inputs averaged in.**

### 1.5 `analysis/FeedbackGenerator.kt`

Builds per-phase notes such as "Knee bend (deepest): N° (target 100–160°)" and
"Elbow extension at apex: N° (target 150–180°)". The numbers come from 1.1 to 1.3. The
target ranges are the invented bands above, presented as targets with no source. The
RELEASE phase score is `(elbowAngleScore + elbowFlareScore) / 2`, so it mixes a side-view
metric with a front-view metric.

### 1.6 `analysis/ShotPhaseClassifier.kt`

State machine driven by right-wrist Y deltas in **raw pixels** (`-2f`, `-1.5f`, `-0.5f`).
The thresholds depend on video resolution and distance from the camera. Right hand
hardcoded. Status: **Assumed.**

### 1.7 `ui/screens/ResultsScreen.kt`

| UI element | Status | Problem |
|---|---|---|
| "Elbow Extension" bar | **Hardcoded mapping** | `elbowAngleAtRelease / 180 * 100`. This is not a score: 90° shows as 50 %. |
| "Knee Bend" bar | **Hardcoded mapping** | `kneeBendDepth / 180 * 100`. A *deeper* bend gets a *lower* bar, which contradicts the scorer and the tutorial. |
| `IdealFormCard` | Hardcoded | Targets "150°–180°", "100°–160°", "≥ 80" are restated with no source. |
| `getDrillsForSession` | Assumed | Thresholds (`< 80`, `< 150f`, `> 160f`) duplicated from the scorers. Fires even when the metric was a placeholder. |
| Share text | Hardcoded targets | Repeats the invented ranges. |

### 1.8 Other UI

- `ui/components/SkeletonOverlay.kt` `SegmentColors.fromResult`: colors bones by the scores
  above, so a placeholder 85 paints the guide arm green.
- `ui/screens/AnalysisScreen.kt` `loadingTips`: "Film from a 45° angle". This is the worst
  view for most metrics (see section 3b of the v2 spec). It must change.
- `ui/screens/TutorialScreen.kt`: "45°–52° launch angle", "90° L", "100°–160°", "150°–180°"
  are static coaching text. They must either cite a source or be labeled as coaching
  convention, and must match `ScoringConfig`.
- `ui/screens/HomeScreen.kt` `StatsBanner`: average and best are derived from stored
  overall scores, so they inherit the problems above. Labelled **Measured (derived)**.
- `ui/screens/ProgressScreen.kt`: charts stored values. Fine mechanically, but it charts the
  placeholder-polluted overall score.

### 1.9 `util/LandmarkMapper.kt`

- Missing eyes/ears/index fingers are **silently substituted** with nose/eye/wrist positions.
- `confidence` (mean `inFrameLikelihood`) is computed but **never used** for gating.
- If any of the 12 key joints is missing, the whole frame returns `null`, and
  `PoseEstimator.detectVideo` **drops** it. Frame index then no longer equals
  `time / 100 ms`, so the overlay drifts out of sync with the video after the first dropped
  frame. This is a correctness bug, not only an accuracy one.

---

## 2. How media is stored today

- **Upload**: `HomeScreen` uses `ActivityResultContracts.PickVisualMedia`. The returned
  `content://media/picker/...` URI is passed through navigation and stored verbatim in
  `SessionEntity.mediaUri`. The read grant on a Photo Picker URI is temporary and not
  persistable, so **after process death or restart the URI can no longer be opened**. This is
  why saved videos "disappear".
- **Record**: `CaptureScreen` writes to MediaStore `Movies/FormCheck/FormCheck_<ts>.mp4` and
  stores that MediaStore URI. It survives restarts, but it lives in shared storage, can be
  deleted by the user from the gallery, and is not tied to the session lifecycle.
- **Landmarks are never saved.** `AnalysisResult.landmarksByFrame` exists only in memory.
  The Results screen cannot replay the overlay, and does not show the video at all.
- Room DB `formcheck.db`, version 1, single table `sessions` (id, timestamp, mediaUri,
  overallScore, elbowFlareScore, elbowAngleAtRelease, kneeBendDepth, balanceScore,
  guideHandScore, phaseFeedbackJson). `exportSchema = true`, schema 1.json is committed. No
  migrations. No session type, no file paths, no per-metric evidence.

## 3. Sampling rate, pose model, shooting hand

| Item | v1 behaviour |
|---|---|
| Frame sampling | `MediaMetadataRetriever.getFrameAtTime(t, OPTION_CLOSEST)` every **100 ms (10 fps)**, timestamps synthesized as `index × 100 ms`. A release (~100 ms) falls between samples. All bitmaps of the clip are decoded and held in memory before detection starts (OOM risk on long clips). `OPTION_CLOSEST` decodes from the previous keyframe for every sample, so it is also slow. |
| Pose model | **ML Kit Pose Detection, accurate model** (`pose-detection-accurate:18.0.0-beta5`) in `SINGLE_IMAGE_MODE`. 2D image landmarks plus a relative z. No world landmarks, no temporal tracking, no smoothing. |
| Shooting hand | **Right hand assumed everywhere**: `AngleEvaluator`, `ShotPhaseClassifier`, `GuideHandDetector` (left = guide), `SkeletonOverlay` (right arm = "shooting arm"). No setting, no detection. |
| Camera view | Not detected. All metrics are computed regardless of view. |
| Ball / rim | Not detected at all. "Release" is inferred from wrist/elbow motion only. |
| Real-world scale | None. Everything is pixels or ratios. |
| Photos | Accepted. A single frame is analyzed as if it contained every phase of the shot. |

## 4. Other findings

- No unit tests or instrumented tests exist.
- No Gradle wrapper scripts (`gradlew`) are committed, only `gradle-wrapper.properties`.
- `compileSdk = 34`. Current AndroidX releases (CameraX 1.6, Media3 1.11, Activity 1.13)
  require `compileSdk 36`, and the newest Core/Navigation require 37 and AGP 9.1.
- Analysis runs in a `viewModelScope` coroutine. Leaving the Analysis screen cancels it and
  loses the work.
- Thresholds are scattered across five classes and two screens.

---

## 5. What must change, mapped to v2 phases

| # | Change | Phase |
|---|---|---|
| 1 | Remove every placeholder (85 / "looks neutral"), every zero-on-empty result, and the `/180*100` bar mappings. Introduce a `Metric` model with value, unit, confidence, frame range, and an explicit **Not measured + reason** state. | 2 (model), 4 (UI) |
| 2 | Replace ML Kit + `MediaMetadataRetriever` 10 fps sampling with **MediaExtractor + MediaCodec** decoding at native frame rate with real presentation timestamps, feeding **MediaPipe Pose Landmarker (heavy, GPU→CPU fallback)** in VIDEO mode. Use world landmarks for angles, image landmarks for drawing. | 2 |
| 3 | Never drop frames from the timeline. Store a per-frame record even when no pose is found, so index ↔ timestamp stays exact. | 2 |
| 4 | One Euro filter per coordinate; velocities from timestamps. | 2 |
| 5 | Visibility gating at 0.6; a metric that depends on a gated landmark becomes Not measured. | 2 |
| 6 | Camera view classifier (side / front / angled) and the metric-validity table from spec 3b. | 2 |
| 7 | Remove right-hand assumption: onboarding (hand, height), auto-detect from wrist under ball, mismatch warning. | 2 (logic), 9 (onboarding UI) |
| 8 | Scale calibration (rim, ball diameter, user height). | 2 (height), 5 (ball/rim) |
| 9 | Copy media into `filesDir/sessions/{id}/`, persist landmarks, ball track, thumbnail, results. Room v2 with a tested migration from v1. | 3 |
| 10 | Long analyses in a foreground WorkManager job. | 3 |
| 11 | Library screen; playback that redraws overlays from saved landmarks with toggles, slow motion and frame stepping; annotated MP4 export. | 3 |
| 12 | Reusable score dashboard (ring, rows, confidence, color + number + icon, biggest-fix card, tap-to-frame). | 4 |
| 13 | `BallRimDetector` interface: learned detector + classical fallback, Kalman tracking. Release from ball separation, parabola fit (RANSAC) for release angle, apex, entry angle. Shot timing, follow-through hold, balance drift. | 5 |
| 14 | All target ranges in `ScoringConfig` with source notes. Distance-from-range scoring, consistency score, overall = mean of measured metrics with "N of M" count. | 2 (config), 5 (consistency) |
| 15 | Shootaround mode. | 6 |
| 16 | Dribbling practice. | 7 |
| 17 | Vertical jump. | 8 |
| 18 | Camera setup guide, progress, drill recommendations from measured metrics, best/worst shot, goals/streaks, settings, accessibility, permission rationale. Rewrite tutorial and loading tips to match `ScoringConfig` and the view rules. | 9 |
| 19 | Unit tests for all geometry; `validation/` folder and instrumented MAE test; README with results, architecture, limitations, model licenses. | every phase, 10 |
| 20 | Photos: a single still cannot establish dip, set point, release or follow-through timing, so every v2 form metric would be "Not measured". Photo upload is removed. The picker accepts video only. | 3 |
| 21 | Build: add Gradle wrapper scripts; move to compileSdk 36 / AGP 8.13 so current CameraX/Media3/WorkManager can be used. | 2 |

### Decision: ML Kit vs. MediaPipe

The spec asks for MediaPipe Pose Landmarker unless ML Kit can be justified. ML Kit is
replaced. It has no metric world landmarks, no VIDEO running mode with temporal tracking,
and no GPU delegate choice. MediaPipe Tasks Vision provides all three on device under the
Apache 2.0 license.
