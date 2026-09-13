# StrikeVision Omega v3 alpha 1

This build replaces the old launcher with a local combat-telemetry lab while preserving the previous MainActivity as a fallback class.

Implemented now:
- front-camera ML Kit pose telemetry
- calibrated mph measurement with shoulder-width fallback
- camera normal/high-speed capability readout
- Quick, Velocity, Power, Combo, and Endurance modes
- orthodox/southpaw-aware hand-technique heuristics
- punch/kick trajectory classification
- peak and average velocity, recoil velocity, acceleration, time-to-peak, burst duration, path length, extension distance
- modeled effective striking mass
- momentum and kinetic-energy potential separated from estimated force window
- kinetic-chain score using hip/shoulder/support-joint contribution and sequencing
- mechanical leak labels
- confidence grades that penalize weak calibration and low inference rate
- local history up to 300 strikes
- technique/stance personal-best map
- session velocity retention, left/right asymmetry, combo transition timing, fatigue and chain insight
- fully local measurements with no required cloud account

Next layers reserved for later revisions:
- full per-frame optical flow between pose detections
- CameraX 120/240 fps high-speed recording path on supported hardware
- synchronized video replay with trajectory/skeleton overlay
- frame-linked coaching evidence
- richer technique classifier trained from labeled combat footage
- Digital Fighter Twin baselines and longitudinal mechanical fingerprints
