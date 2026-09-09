package com.vaan.characterroom;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private FrameLayout root;
    private PreviewView cameraView;
    private CharacterGLView characterView;
    private LinearLayout controls;
    private TextView status;
    private Button stopRecord, restoreUi;
    private Button keyButton, playButton, audioButton, mirrorButton;

    private ProcessCameraProvider cameraProvider;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private ParcelFileDescriptor recordFd;
    private Uri recordUri;
    private boolean recording = false;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else Toast.makeText(this, "Camera permission is required for the live room view.", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<Intent> videoPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                        characterView.loadVideo(uri);
                        status.setText("VIDEO LOADED  •  drag to move  •  pinch/twist to size + rotate");
                    }
                }
            });

    private final ActivityResultLauncher<Intent> projectionPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    beginRecording(result.getResultCode(), result.getData());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        cameraView = new PreviewView(this);
        cameraView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        cameraView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(cameraView, match());

        characterView = new CharacterGLView(this);
        root.addView(characterView, match());

        status = new TextView(this);
        status.setText("IMPORT A VIDEO  •  AUTO key removes edge-matching background");
        status.setTextColor(Color.WHITE);
        status.setTextSize(12f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10), dp(7), dp(10), dp(7));
        status.setBackground(roundBg(0xA8000000, dp(14)));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.TOP;
        sp.setMargins(dp(8), dp(10), dp(8), 0);
        root.addView(status, sp);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(8), dp(7), dp(8), dp(8));
        controls.setBackgroundColor(0xC9000000);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.BOTTOM;
        root.addView(controls, cp);

        LinearLayout row1 = row();
        Button importBtn = button("IMPORT VIDEO");
        Button cameraBtn = button("FLIP CAMERA");
        playButton = button("PAUSE");
        row1.addView(importBtn, weight()); row1.addView(cameraBtn, weight()); row1.addView(playButton, weight());
        controls.addView(row1);

        LinearLayout row2 = row();
        keyButton = button("KEY: AUTO");
        mirrorButton = button("MIRROR: OFF");
        audioButton = button("AUDIO: OFF");
        Button resetBtn = button("RESET");
        row2.addView(keyButton, weight()); row2.addView(mirrorButton, weight()); row2.addView(audioButton, weight()); row2.addView(resetBtn, weight());
        controls.addView(row2);

        controls.addView(sliderRow("REMOVE", 5, 60, 23, p -> characterView.setTolerance(p / 100f)));
        controls.addView(sliderRow("EDGE", 1, 35, 10, p -> characterView.setSoftness(p / 100f)));
        controls.addView(sliderRow("OPACITY", 20, 100, 100, p -> characterView.setOpacity(p / 100f)));
        controls.addView(sliderRow("SHADOW", 0, 60, 25, p -> characterView.setShadow(p / 100f)));

        LinearLayout row3 = row();
        Button snapBtn = button("PHOTO");
        Button recordBtn = button("RECORD");
        Button cleanBtn = button("CLEAN VIEW");
        row3.addView(snapBtn, weight()); row3.addView(recordBtn, weight()); row3.addView(cleanBtn, weight());
        controls.addView(row3);

        stopRecord = button("● STOP RECORDING");
        stopRecord.setTextColor(0xFFFF5252);
        stopRecord.setVisibility(View.GONE);
        FrameLayout.LayoutParams sr = new FrameLayout.LayoutParams(dp(150), dp(48));
        sr.gravity = Gravity.TOP | Gravity.END;
        sr.setMargins(0, dp(12), dp(10), 0);
        root.addView(stopRecord, sr);

        restoreUi = button("UI");
        restoreUi.setTextSize(9f);
        restoreUi.setAlpha(0.65f);
        restoreUi.setVisibility(View.GONE);
        FrameLayout.LayoutParams ru = new FrameLayout.LayoutParams(dp(48), dp(42));
        ru.gravity = Gravity.TOP | Gravity.START;
        ru.setMargins(dp(8), dp(10), 0, 0);
        root.addView(restoreUi, ru);

        importBtn.setOnClickListener(v -> pickVideo());
        cameraBtn.setOnClickListener(v -> { lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK; bindCamera(); });
        playButton.setOnClickListener(v -> { characterView.togglePlay(); main.postDelayed(() -> playButton.setText(characterView.isPlaying() ? "PAUSE" : "PLAY"), 60); });
        keyButton.setOnClickListener(v -> cycleKeyMode());
        mirrorButton.setOnClickListener(v -> { boolean m = !characterView.isMirror(); characterView.setMirror(m); mirrorButton.setText(m ? "MIRROR: ON" : "MIRROR: OFF"); });
        audioButton.setOnClickListener(v -> { boolean a = !characterView.isAudioEnabled(); characterView.setAudioEnabled(a); audioButton.setText(a ? "AUDIO: ON" : "AUDIO: OFF"); });
        resetBtn.setOnClickListener(v -> characterView.resetPlacement());
        snapBtn.setOnClickListener(v -> capturePhoto());
        recordBtn.setOnClickListener(v -> requestRecording());
        cleanBtn.setOnClickListener(v -> setUiVisible(false));
        stopRecord.setOnClickListener(v -> stopRecording(true));
        restoreUi.setOnClickListener(v -> setUiVisible(true));

        root.setOnLongClickListener(v -> { if (!recording) { setUiVisible(true); return true; } return false; });
        cameraView.setOnClickListener(v -> { if (!recording && controls.getVisibility() != View.VISIBLE) setUiVisible(true); });
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("video/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        videoPicker.launch(i);
    }

    private void cycleKeyMode() {
        int mode = (characterView.getKeyMode() + 1) % 6;
        characterView.setKeyMode(mode);
        String name;
        switch (mode) {
            case CharacterGLView.KEY_OFF: name = "OFF"; break;
            case CharacterGLView.KEY_GREEN: name = "GREEN"; break;
            case CharacterGLView.KEY_BLUE: name = "BLUE"; break;
            case CharacterGLView.KEY_WHITE: name = "WHITE"; break;
            case CharacterGLView.KEY_BLACK: name = "BLACK"; break;
            default: name = "AUTO";
        }
        keyButton.setText("KEY: " + name);
        status.setText(mode == CharacterGLView.KEY_AUTO ? "AUTO samples all four video corners each frame" : "KEY " + name + "  •  REMOVE controls strength  •  EDGE controls feathering");
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try { cameraProvider = future.get(); bindCamera(); }
            catch (Exception e) { Toast.makeText(this, "Camera failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null) return;
        try {
            cameraProvider.unbindAll();
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(cameraView.getSurfaceProvider());
            CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
            cameraProvider.bindToLifecycle(this, selector, preview);
        } catch (Exception e) {
            Toast.makeText(this, "This camera is unavailable.", Toast.LENGTH_SHORT).show();
        }
    }

    private void capturePhoto() {
        boolean wasControls = controls.getVisibility() == View.VISIBLE;
        controls.setVisibility(View.GONE); status.setVisibility(View.GONE); stopRecord.setVisibility(View.GONE);
        main.postDelayed(() -> {
            Bitmap bitmap = Bitmap.createBitmap(Math.max(2, root.getWidth()), Math.max(2, root.getHeight()), Bitmap.Config.ARGB_8888);
            PixelCopy.request(getWindow(), bitmap, result -> {
                if (result == PixelCopy.SUCCESS) saveBitmap(bitmap); else Toast.makeText(this, "Photo capture failed.", Toast.LENGTH_SHORT).show();
                if (wasControls && !recording) { controls.setVisibility(View.VISIBLE); status.setVisibility(View.VISIBLE); }
                if (recording) stopRecord.setVisibility(View.VISIBLE);
            }, main);
        }, 120);
    }

    private void saveBitmap(Bitmap bitmap) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "CharacterRoom_" + stamp + ".jpg");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CharacterRoom");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("MediaStore insert failed");
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out); }
            } else {
                File dir = new File(getExternalFilesDir(null), "CharacterRoom"); dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, "CharacterRoom_" + stamp + ".jpg"))) { bitmap.compress(Bitmap.CompressFormat.JPEG,95,out); }
            }
            Toast.makeText(this, "Photo saved to CharacterRoom.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void requestRecording() {
        if (recording) { stopRecording(true); return; }
        projectionPermission.launch(projectionManager.createScreenCaptureIntent());
    }

    private void beginRecording(int resultCode, Intent data) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int srcW = dm.widthPixels, srcH = dm.heightPixels;
            float scale = Math.min(1f, 1080f / Math.max(srcW, srcH));
            int w = even((int)(srcW * scale));
            int h = even((int)(srcH * scale));
            int density = dm.densityDpi;

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoEncodingBitRate(10_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(w, h);
            recorder.setOrientationHint(0);

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, "CharacterRoom_" + stamp + ".mp4");
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CharacterRoom");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                recordUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (recordUri == null) throw new IOException("Could not create recording");
                recordFd = getContentResolver().openFileDescriptor(recordUri, "w");
                if (recordFd == null) throw new IOException("Could not open recording file");
                recorder.setOutputFile(recordFd.getFileDescriptor());
            } else {
                File dir = new File(getExternalFilesDir(null), "CharacterRoom"); dir.mkdirs();
                recorder.setOutputFile(new File(dir, "CharacterRoom_" + stamp + ".mp4").getAbsolutePath());
            }

            recorder.prepare();
            projection = projectionManager.getMediaProjection(resultCode, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> { if (recording) stopRecording(true); }); }
            }, main);
            virtualDisplay = projection.createVirtualDisplay("CharacterRoomRecorder", w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, main);
            recorder.start();
            recording = true;
            immersive(true);
            controls.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            stopRecord.setVisibility(View.VISIBLE);
            restoreUi.setVisibility(View.GONE);
            Toast.makeText(this, "Recording started. The live composite is being captured.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            cleanupProjection();
            Toast.makeText(this, "Recording could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording(boolean save) {
        if (!recording && recorder == null) return;
        recording = false;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        cleanupProjection();
        if (Build.VERSION.SDK_INT >= 29 && recordUri != null) {
            try {
                ContentValues cv = new ContentValues(); cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(recordUri, cv, null, null);
            } catch (Exception ignored) {}
        }
        recordUri = null;
        immersive(false);
        stopRecord.setVisibility(View.GONE);
        restoreUi.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        if (save) Toast.makeText(this, "Video saved to Movies/CharacterRoom.", Toast.LENGTH_SHORT).show();
    }

    private void cleanupProjection() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        if (recorder != null) { try { recorder.reset(); recorder.release(); } catch (Exception ignored) {} recorder = null; }
        if (recordFd != null) { try { recordFd.close(); } catch (Exception ignored) {} recordFd = null; }
    }

    private void setUiVisible(boolean visible) {
        controls.setVisibility(visible ? View.VISIBLE : View.GONE);
        status.setVisibility(visible ? View.VISIBLE : View.GONE);
        restoreUi.setVisibility((!visible && !recording) ? View.VISIBLE : View.GONE);
        if (!visible) Toast.makeText(this, "Clean view. Tap camera area to restore controls.", Toast.LENGTH_SHORT).show();
    }

    private void immersive(boolean yes) {
        if (yes) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override protected void onResume() { super.onResume(); if (characterView != null) characterView.onHostResume(); }
    @Override protected void onPause() { if (characterView != null) characterView.onHostPause(); super.onPause(); }
    @Override protected void onDestroy() { if (recording) stopRecording(true); if (characterView != null) characterView.release(); if (cameraProvider != null) cameraProvider.unbindAll(); super.onDestroy(); }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER); return l; }
    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(10f); b.setAllCaps(false); b.setPadding(dp(4),0,dp(4),0); b.setBackground(roundBg(0xFF292929, dp(12))); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43), 1f); p.setMargins(dp(3),dp(3),dp(3),dp(3)); b.setLayoutParams(p); return b; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43),1f); p.setMargins(dp(3),dp(3),dp(3),dp(3)); return p; }

    private interface SliderAction { void apply(int progress); }
    private View sliderRow(String name, int min, int max, int value, SliderAction action) {
        LinearLayout r = row();
        TextView t = new TextView(this); t.setText(name); t.setTextColor(0xFFE8E8E8); t.setTextSize(10f); t.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(t, new LinearLayout.LayoutParams(dp(62),dp(32)));
        SeekBar s = new SeekBar(this); s.setMax(max-min); s.setProgress(value-min); s.setPadding(dp(4),0,dp(4),0);
        TextView v = new TextView(this); v.setText(String.valueOf(value)); v.setTextColor(Color.WHITE); v.setTextSize(10f); v.setGravity(Gravity.CENTER);
        r.addView(s, new LinearLayout.LayoutParams(0,dp(32),1f)); r.addView(v,new LinearLayout.LayoutParams(dp(36),dp(32)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { int actual=progress+min; v.setText(String.valueOf(actual)); action.apply(actual); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        action.apply(value);
        return r;
    }

    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }
    private GradientDrawable roundBg(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); return g; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static int even(int v) { return Math.max(2, v - (v % 2)); }
}
