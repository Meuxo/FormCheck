# Validation set

This folder holds real clips with **hand-measured** ground truth. The instrumented test
`ValidationAccuracyTest` runs the full on-device pipeline on each clip and reports the mean
absolute error (MAE) per metric. The results table goes in the main README.

**Status: empty.** No validation clips or ground truth are committed yet, so no accuracy on
real footage has been measured. The test skips (it does not pass vacuously) until rows are added.
Nothing here may be filled with estimated or invented values.

## Adding a clip

1. Record a short clip following the filming guide for its mode (side view for form shots, front
   view for elbow alignment or dribbling, feet visible for jumps). Get consent from anyone filmed;
   the repository is public.
2. Save it as `clips/<name>.mp4`.
3. Measure the ground truth by hand, frame by frame, in a video analysis tool. Kinovea is free
   and has an angle tool; use it outside this project. Record:
   - joint angles at a stated frame (`KNEE_BEND`, `ELBOW_SET`, `ELBOW_RELEASE`, `TRUNK_LEAN`,
     `ELBOW_FLARE`), in degrees;
   - `RELEASE_ANGLE` / `ENTRY_ANGLE` from the ball positions, in degrees;
   - `SHOT_TIMING` and `FLIGHT_TIME` from frame counts ÷ frame rate, in milliseconds;
   - `JUMP_HEIGHT` in centimetres, if a reference such as a jump mat or Vertec is available.
4. Add one row per measured metric to `ground_truth.csv`:

   ```
   clip,metric_id,value,frame,measured_by,notes
   side_ft_01.mp4,KNEE_BEND,118,42,initials,deepest dip, left knee
   ```

   `metric_id` is a `MetricId` name. `value` uses the metric's unit. `frame` is 0-based (optional).
   The test compares against shot 1 of the clip.

## Running

```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.meuxo.formcheck.ValidationAccuracyTest
```

The test logs a table (tag `FormCheckValidation`) and writes
`validation_results.csv` to the app's external files directory on the device:
`Android/data/com.meuxo.formcheck/files/`.

A metric the app reports as Not measured is counted separately, not as an error of zero.
