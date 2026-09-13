# Copy Anything APK

This branch contains a self-contained Android app source bundle and an automated APK build.

## What the app handles

- Plain text and long text
- Web links
- Images
- Audio and video
- PDFs and office documents
- ZIP archives and arbitrary file types
- Multiple files at once
- Content shared into the app from other Android apps
- Mixed text + file clipboard bundles
- System share fallback when a destination app does not support rich clipboard paste
- Saving editor text as a `.txt` file

The app requests no internet permission and no broad storage permission. Files are selected through Android's system document picker and represented as secure `content://` links.
