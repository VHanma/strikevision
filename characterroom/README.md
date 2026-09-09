# Character Room

Android live-camera character compositor.

## v1 features
- Live CameraX room view, front/back camera switching.
- Import any local video.
- GPU video overlay via OpenGL ES external texture.
- Background removal modes: AUTO four-corner adaptive key, green, blue, white, black, or off.
- Removal tolerance, feathered edge softness, opacity, green despill, shadow strength.
- Drag character to move. Pinch to resize. Two-finger twist to rotate.
- Mirror character video, loop playback, optional source audio.
- Clean-view mode.
- Save composited JPEG photos to `Pictures/CharacterRoom`.
- Record the visible live composite to H.264 MP4 in `Movies/CharacterRoom` through Android MediaProjection.

AUTO is intentionally generic rather than human-only segmentation, so it can work with filmed people, costumes, anime clips, game characters, puppets, creatures, and other subjects when their background is reasonably distinct from the subject. Manual key modes are included for tougher sources.
