# Eye of Hanma: Strike Lab

Original mobile-first PWA inspired by the *idea* of simple punch-speed apps, but built around camera pose tracking instead of swinging the phone.

## What is implemented
- Front/selfie camera
- MediaPipe Pose Landmarker client-side
- Wrist / elbow / shoulder / hip overlay
- Camera-FPS and landmark-confidence HUD
- Per-hand punch state machine with onset, acceleration, peak, extension, retraction and cooldown
- Idle-motion suppression using speed, acceleration, displacement and confidence thresholds
- MPH + ft/s
- Estimated force range in lbf, kinetic energy in ft-lb, momentum in lb·s
- Jab / cross / hook / uppercut heuristic labels
- Single Punch, Speed Test, Combo, Reaction, Technique, Records
- LocalStorage history and profile
- Accuracy Lab
- Demo Simulation clearly marked as demo
- PWA manifest + service worker

## Important accuracy note
Camera speed depends heavily on calibration, camera angle, frame rate, landmark quality and motion blur. Force is an impulse-based estimate, not direct force-sensor data.

## Run on Android / Termux
Camera access requires HTTPS or localhost.

```bash
cd eye_of_hanma_strike_lab
python -m http.server 8080
```

Open `http://127.0.0.1:8080` in Chrome, allow camera permission, then Add to Home screen if desired.

## Calibration
Open a camera mode first so a body is being tracked. Extend one arm fully, then go to Accuracy Lab and use **Capture Extended-Arm Scale**.
