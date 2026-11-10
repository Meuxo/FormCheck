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
