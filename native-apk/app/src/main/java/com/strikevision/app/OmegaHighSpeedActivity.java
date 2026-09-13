package com.strikevision.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.DynamicRange;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.HighSpeedVideoSessionConfig;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapabilities;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OmegaHighSpeedActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION = 402;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PreviewView preview;
    private TextView status, mode, countdown;
    private Button recordButton;
    private ProcessCameraProvider provider;
    private Recorder recorder;
    private Recording recording;
    private int selectedSeconds = 5;
    private int captureFps = 30;
    private boolean highSpeed = false;
    private File currentFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else bindBestCamera();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) bindBestCamera();
        else status.setText("Camera permission is required.");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { if (recording != null) recording.stop(); } catch (Throwable ignored) {}
        ui.removeCallbacksAndMessages(null);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(22, 34, 22, 26);
        panel.setBackgroundColor(Color.argb(125, 0, 0, 0));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(panel, pp);
        setContentView(root);

        TextView title = text("Ω HIGH-SPEED LAB", 22, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        mode = text("Probing front-camera high-speed modes...", 12, Color.rgb(190,255,50), Typeface.BOLD);
        mode.setGravity(Gravity.CENTER);
        panel.addView(mode);
        status = text("Camera starting", 12, Color.LTGRAY, Typeface.NORMAL);
        status.setGravity(Gravity.CENTER);
        panel.addView(status);
        countdown = text("Ready", 24, Color.WHITE, Typeface.BOLD);
        countdown.setGravity(Gravity.CENTER);
        panel.addView(countdown);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        for (int s : new int[]{3,5,10,15}) {
            Button b = small(s + " sec");
            b.setOnClickListener(v -> { selectedSeconds = s; status.setText(s + " second high-speed capture selected"); });
            times.addView(b);
        }
        panel.addView(times);

        recordButton = new Button(this);
        recordButton.setText("START 5-COUNT");
        recordButton.setAllCaps(false);
        recordButton.setTextColor(Color.WHITE);
        recordButton.setTextSize(16);
        recordButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        recordButton.setBackground(round(Color.rgb(45,118,242)));
        recordButton.setEnabled(false);
        recordButton.setOnClickListener(v -> countdownAndRecord());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 104);
        rp.setMargins(0, 10, 0, 0);
        panel.addView(recordButton, rp);
    }

    private void bindBestCamera() {
        status.setText("Finding fastest stable front-camera recording mode...");
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                CameraInfo info = provider.getCameraInfo(selector);
                VideoCapabilities hsCaps = Recorder.getHighSpeedVideoCapabilities(info);
                if (hsCaps != null && !hsCaps.getSupportedQualities(DynamicRange.SDR).isEmpty()) {
                    if (bindHighSpeed(selector, info, hsCaps)) return;
                }
                bindStandard(selector, info);
            } catch (Throwable t) {
                status.setText("Camera setup failed: " + t.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean bindHighSpeed(CameraSelector selector, CameraInfo info, VideoCapabilities caps) {
        try {
            List<Quality> qualities = new ArrayList<>(caps.getSupportedQualities(DynamicRange.SDR));
            if (qualities.isEmpty()) return false;
            Quality quality = chooseQuality(qualities);
            recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build();
            VideoCapture<Recorder> video = VideoCapture.withOutput(recorder);
            Preview p = new Preview.Builder().build();
            p.setSurfaceProvider(preview.getSurfaceProvider());
            HighSpeedVideoSessionConfig.Builder builder = new HighSpeedVideoSessionConfig.Builder(video)
                    .setPreview(p)
                    .setSlowMotionEnabled(false);
            Set<Range<Integer>> supported = info.getSupportedFrameRateRanges(builder.build());
            Range<Integer> best = chooseHighSpeedRange(supported);
            if (best == null || best.getUpper() < 120) return false;
            builder.setFrameRateRange(best);
            if (!info.isSessionConfigSupported(builder.build())) return false;
            provider.unbindAll();
            provider.bindToLifecycle(this, selector, builder.build());
            captureFps = best.getUpper();
            highSpeed = true;
            mode.setText(String.format(Locale.US, "TRUE HIGH-SPEED: %d fps | %s", captureFps, quality));
            status.setText("High-speed stream armed. Recording stays at capture frame rate for frame-truth analysis.");
            recordButton.setEnabled(true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void bindStandard(CameraSelector selector, CameraInfo info) {
        try {
            VideoCapabilities caps = Recorder.getVideoCapabilities(info);
            List<Quality> qualities = caps.getSupportedQualities(DynamicRange.SDR);
            Quality quality = chooseQuality(qualities);
            recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build();
            VideoCapture<Recorder> video = VideoCapture.withOutput(recorder);
            Preview p = new Preview.Builder().build();
            p.setSurfaceProvider(preview.getSurfaceProvider());
            provider.unbindAll();
            provider.bindToLifecycle(this, selector, p, video);
            int max = 30;
            for (Range<Integer> r : info.getSupportedFrameRateRanges()) max = Math.max(max, r.getUpper());
            captureFps = Math.min(60, max);
            highSpeed = false;
            mode.setText(String.format(Locale.US, "STANDARD FALLBACK: recording profile | camera AE up to %d fps", max));
            status.setText("Front camera exposes no CameraX high-speed session. Offline analyzer still runs flow-assisted reconstruction.");
            recordButton.setEnabled(true);
        } catch (Throwable t) {
            status.setText("Fallback camera failed: " + t.getClass().getSimpleName());
        }
    }

    private Quality chooseQuality(List<Quality> qualities) {
        if (qualities == null || qualities.isEmpty()) return Quality.HD;
        if (qualities.contains(Quality.FHD)) return Quality.FHD;
        if (qualities.contains(Quality.HD)) return Quality.HD;
        return qualities.get(0);
    }

    private Range<Integer> chooseHighSpeedRange(Set<Range<Integer>> ranges) {
        if (ranges == null || ranges.isEmpty()) return null;
        List<Range<Integer>> list = new ArrayList<>(ranges);
        list.sort(Comparator.comparingInt((Range<Integer> r) -> scoreRange(r)).reversed());
        return list.get(0);
    }

    private int scoreRange(Range<Integer> r) {
        int u = r.getUpper();
        if (u == 240) return 10000 + (r.getLower().equals(r.getUpper()) ? 500 : 0);
        if (u == 120) return 9000 + (r.getLower().equals(r.getUpper()) ? 500 : 0);
        if (u > 240) return 8000 + Math.min(u, 960);
        return u;
    }

    private void countdownAndRecord() {
        if (recording != null || recorder == null) return;
        recordButton.setEnabled(false);
        countdown.setText("5");
        for (int i=4;i>=1;i--) {
            final int n=i;
            ui.postDelayed(() -> countdown.setText(String.valueOf(n)), (5L-i)*1000L);
        }
        ui.postDelayed(this::startRecording, 5000L);
    }

    private void startRecording() {
        try {
            File base = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "StrikeVisionOmega");
            if (!base.exists()) base.mkdirs();
            currentFile = new File(base, "omega_" + System.currentTimeMillis() + "_" + captureFps + "fps.mp4");
            FileOutputOptions out = new FileOutputOptions.Builder(currentFile).build();
            recording = recorder.prepareRecording(this, out).start(ContextCompat.getMainExecutor(this), ev -> {
                if (ev instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) ev;
                    recording = null;
                    if (fin.hasError()) {
                        status.setText("Recording finalize error: " + fin.getError());
                        recordButton.setEnabled(true);
                        return;
                    }
                    countdown.setText("Captured");
                    status.setText("Video saved. Launching frame-truth reconstruction...");
                    Intent i = new Intent(this, OmegaOfflineAnalyzerActivity.class);
                    i.putExtra(OmegaOfflineAnalyzerActivity.EXTRA_VIDEO_PATH, currentFile.getAbsolutePath());
                    i.putExtra(OmegaOfflineAnalyzerActivity.EXTRA_CAPTURE_FPS, captureFps);
                    i.putExtra(OmegaOfflineAnalyzerActivity.EXTRA_HIGH_SPEED, highSpeed);
                    startActivity(i);
                    recordButton.setEnabled(true);
                }
            });
            countdown.setText("REC");
            status.setText(String.format(Locale.US, "Capturing %d seconds at target %d fps", selectedSeconds, captureFps));
            ui.postDelayed(() -> {
                try { if (recording != null) recording.stop(); } catch (Throwable ignored) {}
            }, selectedSeconds * 1000L);
        } catch (Throwable t) {
            status.setText("Recording failed: " + t.getClass().getSimpleName());
            recordButton.setEnabled(true);
        }
    }

    private Button small(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setBackground(round(Color.rgb(28,34,43)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 82, 1);
        lp.setMargins(3,3,3,3);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setPadding(0,4,0,4);
        return t;
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(22f);
        g.setStroke(1, Color.rgb(55,62,72));
        return g;
    }
}
