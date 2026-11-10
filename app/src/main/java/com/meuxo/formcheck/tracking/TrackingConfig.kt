package com.meuxo.formcheck.tracking

/**
 * Every non-scoring threshold used by tracking and event detection lives here.
 * Target ranges for scoring live in [com.meuxo.formcheck.scoring.ScoringConfig].
 *
 * Values marked HEURISTIC were chosen by reasoning about the geometry and noise
 * level of the pose model, not taken from a study. They are starting points to
 * tune against the validation set (see /validation).
 */
object TrackingConfig {
    // ---- Pose ------------------------------------------------------------------------------
    /** Landmarks below this visibility are treated as missing (spec 3a). */
    const val VISIBILITY_THRESHOLD = 0.6f

    /** Longest side of the bitmap fed to the pose model. HEURISTIC: trade-off of speed vs detail. */
    const val POSE_INPUT_LONG_SIDE = 960

    /** Longest side of the bitmap fed to the ball/rim detectors. */
    const val DETECTOR_INPUT_LONG_SIDE = 640

    // ---- One Euro filter (Casiez, Roussel & Vogel, CHI 2012) ---------------------------------
    // HEURISTIC parameters. minCutoff sets jitter removal at rest, beta reduces lag when moving.
    const val EURO_MIN_CUTOFF_HZ = 1.7
    const val EURO_BETA_PIXELS = 0.004   // image landmarks, units px/s
    const val EURO_BETA_METERS = 0.8     // world landmarks, units m/s
    const val EURO_D_CUTOFF_HZ = 1.0

    // ---- Camera view classification (HEURISTIC) ---------------------------------------------
    /** Shoulder width / torso height in the image above this → front-on. Adult frontal ≈ 0.7–0.9. */
    const val VIEW_FRONT_MIN_RATIO = 0.55
    /** Below this → side-on (shoulders overlap). */
    const val VIEW_SIDE_MAX_RATIO = 0.30
    /** |z(left shoulder) - z(right shoulder)| / 3D shoulder width above this → side-on. */
    const val VIEW_SIDE_MIN_DEPTH_RATIO = 0.75
    /** Below this depth ratio the shoulders face the camera. */
    const val VIEW_FRONT_MAX_DEPTH_RATIO = 0.40
    /** Confidence multiplier for metrics marked "low confidence" in the view table. */
    const val LOW_CONFIDENCE_VIEW_FACTOR = 0.5

    // ---- Ball tracking ----------------------------------------------------------------------
    /** Detections from the learned model below this score fall back to the classical detector. */
    const val LEARNED_BALL_MIN_SCORE = 0.35f
    /** PHYSICS: a hard jump shot leaves the hand at ~7–9 m/s; 20 m/s is a generous ceiling. */
    const val MAX_BALL_SPEED_MPS = 20.0
    /** Fallback ceiling when no metric scale exists: frame diagonals per second. */
    const val MAX_BALL_SPEED_DIAGONALS_PER_SEC = 4.0
    /** Chi-square gate for 2 dof at p≈0.999. */
    const val KALMAN_GATE_CHI2 = 13.8
    /** Frames the tracker may coast on prediction before it gives up. */
    const val KALMAN_MAX_COAST_FRAMES = 6

    // ---- Shot events ------------------------------------------------------------------------
    /** Release: ball surface must be this many ball diameters from the shooting hand (spec 3e). */
    const val RELEASE_SEPARATION_DIAMETERS = 0.5
    /** ...and keep moving away for at least this long. */
    const val RELEASE_CONFIRM_MS = 50.0
    /** Parabola fit: an inlier lies within this many ball radii of the curve. */
    const val ARC_INLIER_RADII = 0.6
    /** Parabola fit residual (RMS, in ball radii) at which confidence reaches 0. */
    const val ARC_MAX_RESIDUAL_RADII = 1.2
    /** Minimum post-release ball positions needed to fit an arc. */
    const val ARC_MIN_POINTS = 6
    /** Follow-through ends when the shooting elbow bends below this (degrees). */
    const val FOLLOW_THROUGH_MIN_ELBOW_DEG = 140.0

    // ---- Feet / jumps -----------------------------------------------------------------------
    /** A toe is "off the floor" when it rises this fraction of standing stature above the floor line. */
    const val FOOT_LIFT_FRACTION_OF_STATURE = 0.015
    /** Minimum flight time for an event to count as a jump (ms). Shorter is noise or a heel raise. */
    const val MIN_FLIGHT_MS = 120.0
    /** Methods agree if they are within this fraction (spec 7b). */
    const val JUMP_METHOD_TOLERANCE = 0.10
    /** Landing check: knee or ankle angle change between takeoff and landing that flags the jump. */
    const val LANDING_ANGLE_DIFF_DEG = 15.0

    // ---- Anthropometrics --------------------------------------------------------------------
    /**
     * Eye height as a fraction of stature. Average adult value from anthropometric tables
     * (roughly 0.93–0.94). Used only to estimate stature in pixels from eyes-to-heel distance.
     */
    const val EYE_HEIGHT_FRACTION_OF_STATURE = 0.936

    // ---- Shootaround ------------------------------------------------------------------------
    /** Window after the ball crosses the rim plane in which it must appear in the net area (ms). */
    const val MAKE_CONFIRM_WINDOW_MS = 600.0
    /** Rim centre moving more than this fraction of rim width means the camera moved. */
    const val RIM_MOVED_FRACTION = 0.6
    /** Live mode drops to record-only when processing falls below this rate (frames/s). */
    const val LIVE_MIN_FPS = 15.0

    // ---- Dribbling --------------------------------------------------------------------------
    /** A bounce is a down→up velocity reversal within this fraction of hip height above the floor. */
    const val BOUNCE_FLOOR_BAND_FRACTION = 0.25
    /** Ball farther than this many hip-heights from both wrists, without bouncing, counts as a fumble. */
    const val FUMBLE_DISTANCE_HIP_HEIGHTS = 0.9
    /** Head pitch within this many degrees of level counts as "eyes up" (HEURISTIC proxy). */
    const val EYES_UP_MAX_PITCH_DEG = 20.0
    /** Minimum seconds between voice cues. */
    const val VOICE_CUE_MIN_INTERVAL_SEC = 4.0
}
