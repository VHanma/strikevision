# StrikeVision

StrikeVision is a camera-powered boxing and fitness coaching app built with React Native and Expo.

## Features

- **Strike Speed** — camera session velocity test with countdown + speed metric
- **Impact Score** — per-punch power estimation from motion analysis
- **Form Check** — record combinations, replay clips, basic feedback labels
- **Fight Camp** — timed round drills (jab / cross / jab-cross) with audio cues
- **Progress Dashboard** — best session, average speed, total workouts, streaks
- **Coach Chat** — AI-assisted coaching feedback (Phase 3)
- **Pro Subscription** — advanced reports, saved history, premium drills

## Stack

| Layer | Tech |
|---|---|
| Framework | React Native + Expo 49 |
| Language | TypeScript |
| Navigation | Expo Router (file-based) |
| Camera | expo-camera |
| Audio | expo-av |
| State | Zustand |
| Storage | expo-secure-store |
| Graphics | @shopify/react-native-skia |
| Animations | react-native-reanimated |

## Getting Started

```bash
git clone https://github.com/strikevision-labs/strikevision.git
cd strikevision
npm install
npx expo start
```

Run on Android:
```bash
npx expo run:android
```

Run on iOS:
```bash
npx expo run:ios
```

## Folder Structure

```
strikevision/
  app/
    (tabs)/
      index.tsx          # Home
      progress.tsx       # Progress dashboard
      profile.tsx        # User profile
    auth/
      login.tsx
      signup.tsx
    workout/
      velocity-test.tsx  # Strike Speed session
      strike-form.tsx    # Form Check recording
      live-session.tsx   # Fight Camp live drill
      results.tsx        # Post-session results
    subscription/
      paywall.tsx
    _layout.tsx
  src/
    components/
      ui/                # Buttons, cards, badges
      charts/            # Progress charts
      camera/            # Camera viewfinder, overlays
      workout/           # Round timer, drill cards
    features/
      auth/
      camera/
      workouts/
      analytics/
      subscription/
    hooks/               # useCamera, useTimer, useSession
    services/
      api.ts
      storage.ts
      audio.ts
      permissions.ts
    constants/           # Colors, fonts, drill configs
    utils/               # Speed calc, formatting
    types/               # Shared TypeScript interfaces
    assets/
      images/
      audio/
      videos/
```

## Branding

| Token | Value |
|---|---|
| Black | `#0A0A0A` |
| Electric Blue | `#3B82F6` |
| Neon Green | `#A3FF12` |
| White | `#F5F5F5` |
| Gray | `#171717` |

## MVP Roadmap

### Phase 1
- [ ] Auth + onboarding
- [ ] Camera permission flow
- [ ] Home screen
- [ ] Workout selection
- [ ] Timed drill screen
- [ ] Results screen

### Phase 2
- [ ] Save workout history
- [ ] Progress charts
- [ ] Profile
- [ ] Subscription / paywall

### Phase 3
- [ ] Camera-based strike tracking
- [ ] Replay and review
- [ ] AI feedback
- [ ] Coach Chat

## Package IDs

| Platform | ID |
|---|---|
| Android | `com.strikevision.app` |
| iOS | `com.strikevision.app` |

## License

MIT © strikevision-labs
