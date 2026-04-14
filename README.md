# FormCheck

An Android app that analyzes your basketball shooting form using on-device pose detection. Record or upload a video of your shot, and get instant feedback on what you're doing well and what to work on.

## What It Does

FormCheck breaks your shot into five phases (setup, load, lift, release, follow-through) and scores each one individually. It tracks:

- **Elbow extension** at the point of release
- **Elbow alignment** (whether your elbow stays under the ball or flares out)
- **Knee bend depth** during your loading phase
- **Set point height** relative to your forehead
- **Balance** (center of mass over your base of support)
- **Guide hand passivity** (whether your off-hand stays still at release)

After analysis, you get a color-coded skeleton overlay on your video showing exactly where your form is strong (green), needs some work (yellow), or needs attention (red). The app also suggests specific drills based on your weakest areas.

## Features

- Real-time skeleton tracking overlay on video playback
- Per-body-segment color coding (shooting arm, guide hand, torso, legs)
- Detailed per-phase breakdown with scores and coaching notes
- Side-by-side comparison of your measurements vs. ideal form targets
- Personalized drill recommendations based on your results
- Progress tracking across sessions with trend charts
- Share your results as text
- Step-by-step shooting tutorial with filming tips
- Swipe-to-delete session management
- Material You theming (dynamic colors on Android 12+)

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Pose Detection:** ML Kit Pose Detection (accurate model, fully on-device)
- **Video:** CameraX for recording, ExoPlayer (Media3) for playback
- **Architecture:** MVVM + Clean Architecture
- **DI:** Hilt
- **Database:** Room
- **Image Loading:** Coil

## Project Structure

```
app/src/main/java/com/meuxo/formcheck/
├── analysis/          # Pose evaluation, scoring, phase classification
├── data/              # Room database, repository implementation
├── di/                # Hilt modules
├── domain/            # Models, repository interface, use cases
├── ui/
│   ├── components/    # Skeleton overlay, phase cards, charts
│   ├── navigation/    # Nav graph and route definitions
│   ├── screens/       # Home, Capture, Analysis, Results, Progress, Tutorial
│   └── theme/         # Colors and Material theme
└── util/              # Angle math, landmark mapping
```

## Building

1. Open the project in Android Studio (Ladybug or newer recommended)
2. Sync Gradle
3. Run on a device or emulator with API 26+

No API keys or external services required — all processing happens on-device.

## Camera Angle

For the most accurate analysis, film from a **45-degree angle on your shooting-hand side**, about 8-10 feet away with the camera at waist height. This gives the app a clear view of both your elbow extension and elbow alignment. Make sure your full body (head to feet) is in frame.

## License

MIT
