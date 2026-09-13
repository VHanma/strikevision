package com.strikevision.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION = 77;
    private static final double MPS_TO_MPH = 2.2369362921;
    private static final double MPS2_TO_FTPS2 = 3.280839895;
    private static final double KG_TO_LB = 2.2046226218;
    private static final double J_TO_FTLB = 0.7375621493;
    private static final double KGMPS_TO_LBMFTPS = 7.233013851;
    private static final double N_TO_LBF = 0.2248089431;

    private FrameLayout root;
    private PreviewView previewView;
    private LinearLayout hud;
    private TextView statusText, liveText, timerText, latestText, calibrationText, fpsText;
    private EditText weightInput, armInput, shoulderInput;
    private Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService cameraExecutor;
    private PoseDetector poseDetector;
    private SharedPreferences prefs;

    private boolean processing = false;
    private boolean activeTest = false;
    private boolean calibrating = false;
    private int selectedSeconds = 10;
    private int sensitivity = 2;
    private long testEndUptimeMs = 0;
    private long poseFrames = 0;
    private long fpsWindowStartMs = 0;
    private long fpsWindowFrames = 0;
    private double measuredFps = 0.0;

    private double bodyWeightLb = 150.0;
    private double armLengthIn = 27.0;
    private double shoulderWidthIn = 17.0;
    private double pxPerMeter = 0.0;
    private double calibrationQuality = 0.0;
    private final ArrayList<Double> calibrationSamples = new ArrayList<>();

    private final Map<Integer, Track> tracks = new HashMap<>();
    private final ArrayList<StrikeResult> currentResults = new ArrayList<>();
    private PointF lastShoulderCenter = null;
    private PointF lastHipCenter = null;
    private long lastBodyTsNs = 0;
    private double shoulderCenterSpeed = 0.0;
    private double hipCenterSpeed = 0.0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("strikevision_v2", MODE_PRIVATE);
        cameraExecutor = Executors.newSingleThreadExecutor();
        poseDetector = PoseDetection.getClient(
                new PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        .build());
        loadProfile();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else {
            buildScreen();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            buildScreen();
        } else {
            TextView denied = new TextView(this);
            denied.setText("Camera permission is required for StrikeVision.");
            denied.setTextColor(Color.WHITE);
            denied.setTextSize(20);
            denied.setGravity(Gravity.CENTER);
            denied.setBackgroundColor(Color.BLACK);
            setContentView(denied);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { poseDetector.close(); } catch (Throwable ignored) {}
        try { cameraExecutor.shutdown(); } catch (Throwable ignored) {}
        ui.removeCallbacksAndMessages(null);
    }

    private void buildScreen() {
        root = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.argb(68, 0, 0, 0));
        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(22, 38, 22, 26);
        scroll.addView(hud, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        center("STRIKEVISION 2", 28, Color.WHITE);
        center("Velocity + Power Core", 14, Color.rgb(170, 255, 30));

        statusText = center("Starting front camera...", 14, Color.rgb(170, 255, 30));
        fpsText = center("Pose: 0 fps", 12, Color.LTGRAY);
        timerText = center("Ready", 20, Color.WHITE);
        liveText = center("Live speed: 0.0 mph", 18, Color.rgb(80, 160, 255));
        calibrationText = center(calibrationLabel(), 13, Color.LTGRAY);
        latestText = cardText("Latest strike", "No strike yet");

        hud.addView(section("Fighter profile"));
        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        weightInput = field("Weight lb", fmt(bodyWeightLb));
        armInput = field("Shoulder→wrist in", fmt(armLengthIn));
        shoulderInput = field("Shoulder width in", fmt(shoulderWidthIn));
        profileRow.addView(weightInput);
        profileRow.addView(armInput);
        profileRow.addView(shoulderInput);
        hud.addView(profileRow);

        LinearLayout calRow = new LinearLayout(this);
        calRow.setOrientation(LinearLayout.HORIZONTAL);
        Button calibrate = smallButton("Calibrate scale");
        Button resetCal = smallButton("Reset scale");
        calibrate.setOnClickListener(v -> beginCalibration());
        resetCal.setOnClickListener(v -> resetCalibration());
        calRow.addView(calibrate);
        calRow.addView(resetCal);
        hud.addView(calRow);

        hud.addView(section("Test length"));
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int seconds : new int[]{5, 10, 15, 30}) {
            Button b = smallButton(seconds + "s");
            b.setOnClickListener(v -> {
                selectedSeconds = seconds;
                statusText.setText(seconds + " second test selected");
            });
            timeRow.addView(b);
        }
        hud.addView(timeRow);

        hud.addView(section("Strike filter"));
        LinearLayout sensRow = new LinearLayout(this);
        sensRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Strict", "Balanced", "Sensitive"};
        for (int i = 0; i < names.length; i++) {
            final int s = i + 1;
            Button b = smallButton(names[i]);
            b.setOnClickListener(v -> {
                sensitivity = s;
                statusText.setText(names[s - 1] + " strike detection");
            });
            sensRow.addView(b);
        }
        hud.addView(sensRow);

        View spacer = new View(this);
        hud.addView(spacer, new LinearLayout.LayoutParams(1, 40));

        Button start = largeButton("START 5-COUNT");
        start.setOnClickListener(v -> startCountdown());
        hud.addView(start);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        Button history = smallButton("History");
        Button profile = smallButton("Save profile");
        history.setOnClickListener(v -> showHistory());
        profile.setOnClickListener(v -> {
            if (readProfileInputs()) {
                saveProfile();
                statusText.setText("Profile saved");
            }
        });
        bottomRow.addView(history);
        bottomRow.addView(profile);
        hud.addView(bottomRow);

        center("Calibrate with one arm fully extended across the camera plane. Keep the phone still and test from the same position.", 11, Color.LTGRAY);
        bindCamera();
        startUiLoop();
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
                statusText.setText("Pose live. Side view works best for punch-speed testing.");
            } catch (Throwable t) {
                statusText.setText("Camera error: " + t.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void analyzeFrame(ImageProxy proxy) {
        if (processing) {
            proxy.close();
            return;
        }
        Image image = proxy.getImage();
        if (image == null) {
            proxy.close();
            return;
        }
        processing = true;
        long tsNs = proxy.getImageInfo().getTimestamp();
        InputImage input = InputImage.fromMediaImage(image, proxy.getImageInfo().getRotationDegrees());
        poseDetector.process(input)
                .addOnSuccessListener(pose -> processPose(pose, tsNs))
                .addOnCompleteListener(task -> {
                    processing = false;
                    proxy.close();
                });
    }

    private void processPose(Pose pose, long tsNs) {
        poseFrames++;
        updateFps();

        PoseLandmark ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);

        PointF shoulderCenter = centerOf(ls, rs);
        PointF hipCenter = centerOf(lh, rh);
        PointF torso = centerOfPoints(shoulderCenter, hipCenter);
        if (torso == null) return;

        if (calibrating) collectCalibration(pose);
        if (pxPerMeter <= 0.0) updateFallbackScale(ls, rs);
        updateBodySpeeds(shoulderCenter, hipCenter, tsNs);

        double fastest = 0.0;
        String fastestName = "";
        int[] weaponIds = {
                PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_ANKLE,
                PoseLandmark.RIGHT_ANKLE
        };
        for (int id : weaponIds) {
            PoseLandmark lm = pose.getPoseLandmark(id);
            if (!good(lm)) continue;
            Track tr = tracks.get(id);
            if (tr == null) {
                tr = new Track(id);
                tracks.put(id, tr);
            }
            double confidence = lm.getInFrameLikelihood();
            StrikeResult emitted = tr.update(lm.getPosition(), torso, tsNs, currentScale(), confidence, pose);
            if (tr.velocityMps > fastest) {
                fastest = tr.velocityMps;
                fastestName = limbName(id);
            }
            if (emitted != null) onStrike(emitted);
        }

        final double liveMph = fastest * MPS_TO_MPH;
        final String liveName = fastestName;
        ui.post(() -> {
            if (liveText != null) liveText.setText(String.format(Locale.US, "%s  %.1f mph", liveName, liveMph));
        });
    }

    private double currentScale() {
        return pxPerMeter > 1.0 ? pxPerMeter : 500.0;
    }

    private void beginCalibration() {
        if (!readProfileInputs()) return;
        calibrating = true;
        calibrationSamples.clear();
        statusText.setText("CALIBRATING: fully extend either arm across the camera plane and hold briefly.");
    }

    private void collectCalibration(Pose pose) {
        PoseLandmark ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lw = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST);
        PoseLandmark rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST);
        double left = good(ls) && good(lw) ? dist(ls.getPosition(), lw.getPosition()) : 0.0;
        double right = good(rs) && good(rw) ? dist(rs.getPosition(), rw.getPosition()) : 0.0;
        double sample = Math.max(left, right);
        if (sample < 100.0) return;
        calibrationSamples.add(sample);
        if (calibrationSamples.size() >= 15) {
            Collections.sort(calibrationSamples);
            double median = calibrationSamples.get(calibrationSamples.size() / 2);
            double spread = percentile(calibrationSamples, 0.8) - percentile(calibrationSamples, 0.2);
            double meters = armLengthIn * 0.0254;
            pxPerMeter = median / Math.max(0.30, meters);
            calibrationQuality = clamp(1.0 - spread / Math.max(1.0, median) * 2.0, 0.65, 1.0);
            prefs.edit()
                    .putFloat("pxPerMeter", (float) pxPerMeter)
                    .putFloat("calQuality", (float) calibrationQuality)
                    .apply();
            calibrating = false;
            ui.post(() -> {
                calibrationText.setText(calibrationLabel());
                statusText.setText("Scale calibrated. Keep the same camera distance for the test.");
            });
        }
    }

    private void resetCalibration() {
        pxPerMeter = 0.0;
        calibrationQuality = 0.0;
        calibrationSamples.clear();
        prefs.edit().remove("pxPerMeter").remove("calQuality").apply();
        calibrationText.setText(calibrationLabel());
        statusText.setText("Scale reset. Calibrate again for accurate mph.");
    }

    private void updateFallbackScale(PoseLandmark ls, PoseLandmark rs) {
        if (!good(ls) || !good(rs)) return;
        double shoulderPx = dist(ls.getPosition(), rs.getPosition());
        if (shoulderPx < 30) return;
        double meters = shoulderWidthIn * 0.0254;
        pxPerMeter = shoulderPx / Math.max(0.20, meters);
        calibrationQuality = 0.42;
    }

    private void updateBodySpeeds(PointF shoulderCenter, PointF hipCenter, long tsNs) {
        if (lastBodyTsNs > 0) {
            double dt = (tsNs - lastBodyTsNs) / 1_000_000_000.0;
            if (dt >= 0.008 && dt <= 0.20 && currentScale() > 1.0) {
                if (shoulderCenter != null && lastShoulderCenter != null) {
                    double v = dist(shoulderCenter, lastShoulderCenter) / currentScale() / dt;
                    shoulderCenterSpeed = shoulderCenterSpeed * 0.55 + v * 0.45;
                }
                if (hipCenter != null && lastHipCenter != null) {
                    double v = dist(hipCenter, lastHipCenter) / currentScale() / dt;
                    hipCenterSpeed = hipCenterSpeed * 0.55 + v * 0.45;
                }
            }
        }
        if (shoulderCenter != null) lastShoulderCenter = new PointF(shoulderCenter.x, shoulderCenter.y);
        if (hipCenter != null) lastHipCenter = new PointF(hipCenter.x, hipCenter.y);
        lastBodyTsNs = tsNs;
    }

    private void startCountdown() {
        if (activeTest) return;
        if (!readProfileInputs()) return;
        saveProfile();
        if (pxPerMeter <= 1.0 || calibrationQuality < 0.60) {
            statusText.setText("Using approximate body scale. Calibrate first for stronger accuracy.");
        }
        currentResults.clear();
        resetTrackState();
        timerText.setText("5");
        for (int i = 4; i >= 1; i--) {
            final int n = i;
            ui.postDelayed(() -> timerText.setText(String.valueOf(n)), (5L - i) * 1000L);
        }
        ui.postDelayed(() -> {
            activeTest = true;
            testEndUptimeMs = android.os.SystemClock.uptimeMillis() + selectedSeconds * 1000L;
            timerText.setText("GO");
            statusText.setText("Tracking peak limb velocity + power metrics");
        }, 5000L);
        ui.postDelayed(this::finishTest, 5000L + selectedSeconds * 1000L + 250L);
    }

    private void finishTest() {
        if (!activeTest) return;
        activeTest = false;
        for (Track tr : tracks.values()) {
            StrikeResult tail = tr.forceFinish();
            if (tail != null) onStrike(tail);
        }
        timerText.setText("Complete");
        if (currentResults.isEmpty()) {
            statusText.setText("No clean strike burst detected. Try Sensitive or recalibrate.");
            new AlertDialog.Builder(this)
                    .setTitle("Round complete")
                    .setMessage("No clean strike burst detected. Keep the phone fixed, stay side-on, and keep the striking limb in frame.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        double fastest = 0, avg = 0, energy = 0;
        StrikeResult bestEnergy = null;
        for (StrikeResult r : currentResults) {
            fastest = Math.max(fastest, r.peakMph);
            avg += r.peakMph;
            if (r.energyFtLb > energy) {
                energy = r.energyFtLb;
                bestEnergy = r;
            }
        }
        avg /= currentResults.size();
        statusText.setText(currentResults.size() + " strikes saved");
        String msg = String.format(Locale.US,
                "%d strikes\nFastest: %.1f mph\nAverage peak: %.1f mph\nHighest camera-estimated kinetic energy: %.1f ft·lbf%s",
                currentResults.size(), fastest, avg, energy,
                bestEnergy == null ? "" : "\nBest energy strike: " + bestEnergy.name);
        new AlertDialog.Builder(this)
                .setTitle("StrikeVision round")
                .setMessage(msg)
                .setPositiveButton("Run again", (d, w) -> timerText.setText("Ready"))
                .setNegativeButton("History", (d, w) -> showHistory())
                .show();
    }

    private void onStrike(StrikeResult result) {
        if (!activeTest) return;
        currentResults.add(result);
        saveHistory(result);
        String detail = String.format(Locale.US,
                "%s\n%.1f mph peak\n%.0f ft/s² peak accel\nEffective mass: %.1f lb\nMomentum: %.1f lbm·ft/s\nKinetic energy: %.1f ft·lbf\nEstimated force window: %.0f–%.0f lbf\nConfidence: %.0f%%",
                result.name,
                result.peakMph,
                result.peakAccelFt,
                result.effectiveMassLb,
                result.momentumImperial,
                result.energyFtLb,
                result.forceLowLbf,
                result.forceHighLbf,
                result.confidence * 100.0);
        ui.post(() -> latestText.setText(detail));
    }

    private void resetTrackState() {
        tracks.clear();
        lastShoulderCenter = null;
        lastHipCenter = null;
        lastBodyTsNs = 0;
        shoulderCenterSpeed = 0;
        hipCenterSpeed = 0;
    }

    private void updateFps() {
        long now = android.os.SystemClock.uptimeMillis();
        if (fpsWindowStartMs == 0) fpsWindowStartMs = now;
        fpsWindowFrames++;
        long elapsed = now - fpsWindowStartMs;
        if (elapsed >= 1000) {
            measuredFps = fpsWindowFrames * 1000.0 / elapsed;
            fpsWindowFrames = 0;
            fpsWindowStartMs = now;
        }
    }

    private void startUiLoop() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (fpsText != null) fpsText.setText(String.format(Locale.US, "Pose: %.1f fps | frames: %d", measuredFps, poseFrames));
                if (activeTest && timerText != null) {
                    long left = Math.max(0, testEndUptimeMs - android.os.SystemClock.uptimeMillis());
                    timerText.setText(String.format(Locale.US, "%.1fs", left / 1000.0));
                }
                if (calibrationText != null) calibrationText.setText(calibrationLabel());
                ui.postDelayed(this, 250);
            }
        }, 250);
    }

    private String calibrationLabel() {
        if (pxPerMeter > 1.0 && calibrationQuality >= 0.60) {
            return String.format(Locale.US, "Scale: calibrated | confidence %.0f%%", calibrationQuality * 100.0);
        }
        if (pxPerMeter > 1.0) return "Scale: approximate shoulder-width fallback";
        return "Scale: not calibrated";
    }

    private boolean readProfileInputs() {
        try {
            bodyWeightLb = Double.parseDouble(weightInput.getText().toString().trim());
            armLengthIn = Double.parseDouble(armInput.getText().toString().trim());
            shoulderWidthIn = Double.parseDouble(shoulderInput.getText().toString().trim());
            if (bodyWeightLb < 60 || bodyWeightLb > 500 || armLengthIn < 15 || armLengthIn > 45 || shoulderWidthIn < 8 || shoulderWidthIn > 30) {
                throw new IllegalArgumentException();
            }
            return true;
        } catch (Throwable t) {
            new AlertDialog.Builder(this)
                    .setTitle("Check fighter profile")
                    .setMessage("Use pounds and inches. Example: 150 lb, 27 in shoulder-to-wrist, 17 in shoulder width.")
                    .setPositiveButton("OK", null)
                    .show();
            return false;
        }
    }

    private void loadProfile() {
        bodyWeightLb = prefs.getFloat("weightLb", 150f);
        armLengthIn = prefs.getFloat("armIn", 27f);
        shoulderWidthIn = prefs.getFloat("shoulderIn", 17f);
        pxPerMeter = prefs.getFloat("pxPerMeter", 0f);
        calibrationQuality = prefs.getFloat("calQuality", 0f);
    }

    private void saveProfile() {
        prefs.edit()
                .putFloat("weightLb", (float) bodyWeightLb)
                .putFloat("armIn", (float) armLengthIn)
                .putFloat("shoulderIn", (float) shoulderWidthIn)
                .apply();
    }

    private void saveHistory(StrikeResult r) {
        try {
            JSONArray arr;
            String raw = prefs.getString("history", "[]");
            try { arr = new JSONArray(raw); } catch (Throwable t) { arr = new JSONArray(); }
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("name", r.name);
            o.put("mph", r.peakMph);
            o.put("accelFt", r.peakAccelFt);
            o.put("effMassLb", r.effectiveMassLb);
            o.put("momentum", r.momentumImperial);
            o.put("energyFtLb", r.energyFtLb);
            o.put("forceLow", r.forceLowLbf);
            o.put("forceHigh", r.forceHighLbf);
            o.put("confidence", r.confidence);
            arr.put(o);
            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, arr.length() - 200);
            for (int i = start; i < arr.length(); i++) trimmed.put(arr.get(i));
            prefs.edit().putString("history", trimmed.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void showHistory() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("history", "[]"));
            if (arr.length() == 0) {
                new AlertDialog.Builder(this).setTitle("Strike history").setMessage("No saved strikes yet.").setPositiveButton("OK", null).show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, arr.length() - 30);
            for (int i = arr.length() - 1; i >= start; i--) {
                JSONObject o = arr.getJSONObject(i);
                sb.append('#').append(i + 1).append(' ')
                        .append(o.optString("name", "Strike")).append('\n')
                        .append(String.format(Locale.US, "%.1f mph | %.1f ft·lbf | %.0f–%.0f lbf | %.0f%% confidence\n\n",
                                o.optDouble("mph"), o.optDouble("energyFtLb"), o.optDouble("forceLow"), o.optDouble("forceHigh"), o.optDouble("confidence") * 100.0));
            }
            new AlertDialog.Builder(this)
                    .setTitle("Recent strike history")
                    .setMessage(sb.toString())
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Clear history", (d, w) -> {
                        prefs.edit().remove("history").apply();
                        statusText.setText("History cleared");
                    })
                    .show();
        } catch (Throwable t) {
            new AlertDialog.Builder(this).setTitle("History").setMessage("History could not be read.").setPositiveButton("OK", null).show();
        }
    }

    private class Track {
        final int id;
        PointF smoothed = null;
        PointF previousRelative = null;
        long previousTsNs = 0;
        double velocityMps = 0;
        double previousVelocityMps = 0;
        final double[] v3 = new double[3];
        int v3Count = 0;
        int v3Index = 0;
        boolean burst = false;
        double peakMps = 0;
        double peakAccelMps2 = 0;
        double peakConfidence = 0;
        double maxElbowMps = 0;
        double maxShoulderMps = 0;
        double maxHipMps = 0;
        long lastAboveNs = 0;
        long lastEmitNs = 0;

        Track(int id) { this.id = id; }

        StrikeResult update(PointF raw, PointF torso, long tsNs, double scalePxM, double lmConfidence, Pose pose) {
            if (smoothed == null) {
                smoothed = new PointF(raw.x, raw.y);
                previousRelative = new PointF(raw.x - torso.x, raw.y - torso.y);
                previousTsNs = tsNs;
                return null;
            }
            smoothed = new PointF(
                    (float) (smoothed.x * 0.28 + raw.x * 0.72),
                    (float) (smoothed.y * 0.28 + raw.y * 0.72));
            PointF relative = new PointF(smoothed.x - torso.x, smoothed.y - torso.y);
            double dt = (tsNs - previousTsNs) / 1_000_000_000.0;
            if (dt < 0.008 || dt > 0.20) {
                previousRelative = relative;
                previousTsNs = tsNs;
                previousVelocityMps = 0;
                velocityMps = 0;
                return null;
            }

            double instant = dist(relative, previousRelative) / Math.max(1.0, scalePxM) / dt;
            v3[v3Index] = instant;
            v3Index = (v3Index + 1) % 3;
            v3Count = Math.min(3, v3Count + 1);
            double robust = medianVelocity();
            velocityMps = previousVelocityMps * 0.32 + robust * 0.68;
            double accel = (velocityMps - previousVelocityMps) / dt;

            previousRelative = relative;
            previousTsNs = tsNs;
            previousVelocityMps = velocityMps;

            if (!activeTest) return null;
            double threshold = detectionThreshold(id);
            boolean above = velocityMps >= threshold;
            if (!burst) {
                boolean enoughAccel = accel > (isHand(id) ? 5.0 : 7.0);
                if (above && enoughAccel && tsNs - lastEmitNs > 240_000_000L) {
                    burst = true;
                    peakMps = velocityMps;
                    peakAccelMps2 = Math.max(0, accel);
                    peakConfidence = lmConfidence;
                    maxElbowMps = supportElbowSpeed(pose, id, torso, tsNs, scalePxM);
                    maxShoulderMps = shoulderCenterSpeed;
                    maxHipMps = hipCenterSpeed;
                    lastAboveNs = tsNs;
                }
                return null;
            }

            if (above) lastAboveNs = tsNs;
            if (velocityMps > peakMps) {
                peakMps = velocityMps;
                peakConfidence = lmConfidence;
            }
            peakAccelMps2 = Math.max(peakAccelMps2, Math.max(0, accel));
            maxElbowMps = Math.max(maxElbowMps, supportElbowSpeed(pose, id, torso, tsNs, scalePxM));
            maxShoulderMps = Math.max(maxShoulderMps, shoulderCenterSpeed);
            maxHipMps = Math.max(maxHipMps, hipCenterSpeed);

            if (tsNs - lastAboveNs > 120_000_000L) return finishBurst(tsNs);
            return null;
        }

        StrikeResult forceFinish() {
            if (!burst) return null;
            return finishBurst(System.nanoTime());
        }

        private StrikeResult finishBurst(long tsNs) {
            burst = false;
            lastEmitNs = tsNs;
            if (peakMps < detectionThreshold(id) || peakMps > 35.0) {
                resetBurst();
                return null;
            }
            double kg = bodyWeightLb / KG_TO_LB;
            double baseFraction = isHand(id) ? 0.065 : 0.16;
            double elbowRatio = clamp(maxElbowMps / Math.max(0.1, peakMps), 0, 1);
            double shoulderRatio = clamp(maxShoulderMps / Math.max(0.1, peakMps), 0, 0.65);
            double hipRatio = clamp(maxHipMps / Math.max(0.1, peakMps), 0, 0.50);
            double chain = clamp(0.72 + 0.30 * elbowRatio + 0.22 * shoulderRatio + 0.18 * hipRatio, 0.65, 1.22);
            double effectiveMassKg = kg * baseFraction * chain;
            double maxFraction = isHand(id) ? 0.11 : 0.28;
            effectiveMassKg = Math.min(effectiveMassKg, kg * maxFraction);
            double momentum = effectiveMassKg * peakMps;
            double energyJ = 0.5 * effectiveMassKg * peakMps * peakMps;
            double forceLowN = momentum / 0.030;
            double forceHighN = momentum / 0.010;

            double fpsQuality = clamp(measuredFps / 30.0, 0.45, 1.0);
            double speedSanity = peakMps > 1.0 && peakMps < 25.0 ? 1.0 : 0.75;
            double conf = clamp(peakConfidence * Math.max(0.35, calibrationQuality) * fpsQuality * speedSanity, 0.15, 0.99);

            StrikeResult r = new StrikeResult();
            r.name = limbName(id);
            r.peakMph = peakMps * MPS_TO_MPH;
            r.peakAccelFt = peakAccelMps2 * MPS2_TO_FTPS2;
            r.effectiveMassLb = effectiveMassKg * KG_TO_LB;
            r.momentumImperial = momentum * KGMPS_TO_LBMFTPS;
            r.energyFtLb = energyJ * J_TO_FTLB;
            r.forceLowLbf = forceLowN * N_TO_LBF;
            r.forceHighLbf = forceHighN * N_TO_LBF;
            r.confidence = conf;
            resetBurst();
            return r;
        }

        private void resetBurst() {
            peakMps = 0;
            peakAccelMps2 = 0;
            peakConfidence = 0;
            maxElbowMps = 0;
            maxShoulderMps = 0;
            maxHipMps = 0;
            lastAboveNs = 0;
        }

        private double medianVelocity() {
            if (v3Count == 1) return v3[0];
            if (v3Count == 2) return (v3[0] + v3[1]) / 2.0;
            double[] copy = Arrays.copyOf(v3, 3);
            Arrays.sort(copy);
            return copy[1];
        }
    }

    private double supportElbowSpeed(Pose pose, int weaponId, PointF torso, long tsNs, double scalePxM) {
        int elbowId;
        if (weaponId == PoseLandmark.LEFT_WRIST) elbowId = PoseLandmark.LEFT_ELBOW;
        else if (weaponId == PoseLandmark.RIGHT_WRIST) elbowId = PoseLandmark.RIGHT_ELBOW;
        else if (weaponId == PoseLandmark.LEFT_ANKLE) elbowId = PoseLandmark.LEFT_KNEE;
        else elbowId = PoseLandmark.RIGHT_KNEE;
        PoseLandmark joint = pose.getPoseLandmark(elbowId);
        if (!good(joint)) return 0;
        Track jointTrack = tracks.get(elbowId);
        if (jointTrack == null) {
            jointTrack = new Track(elbowId);
            tracks.put(elbowId, jointTrack);
        }
        boolean wasActive = activeTest;
        activeTest = false;
        jointTrack.update(joint.getPosition(), torso, tsNs, scalePxM, joint.getInFrameLikelihood(), pose);
        activeTest = wasActive;
        return jointTrack.velocityMps;
    }

    private double detectionThreshold(int id) {
        double base = isHand(id) ? 1.8 : 2.3;
        if (sensitivity == 1) return base * 1.45;
        if (sensitivity == 3) return base * 0.72;
        return base;
    }

    private boolean isHand(int id) {
        return id == PoseLandmark.LEFT_WRIST || id == PoseLandmark.RIGHT_WRIST;
    }

    private String limbName(int id) {
        if (id == PoseLandmark.LEFT_WRIST) return "Left hand";
        if (id == PoseLandmark.RIGHT_WRIST) return "Right hand";
        if (id == PoseLandmark.LEFT_ANKLE) return "Left kick";
        if (id == PoseLandmark.RIGHT_ANKLE) return "Right kick";
        return "Strike";
    }

    private static class StrikeResult {
        String name;
        double peakMph;
        double peakAccelFt;
        double effectiveMassLb;
        double momentumImperial;
        double energyFtLb;
        double forceLowLbf;
        double forceHighLbf;
        double confidence;
    }

    private boolean good(PoseLandmark lm) {
        return lm != null && lm.getInFrameLikelihood() >= 0.45f;
    }

    private PointF centerOf(PoseLandmark a, PoseLandmark b) {
        if (good(a) && good(b)) {
            return new PointF((a.getPosition().x + b.getPosition().x) / 2f, (a.getPosition().y + b.getPosition().y) / 2f);
        }
        if (good(a)) return a.getPosition();
        if (good(b)) return b.getPosition();
        return null;
    }

    private PointF centerOfPoints(PointF a, PointF b) {
        if (a != null && b != null) return new PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f);
        return a != null ? a : b;
    }

    private double dist(PointF a, PointF b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.round((sorted.size() - 1) * p);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private String fmt(double x) {
        if (Math.abs(x - Math.round(x)) < 0.01) return String.valueOf((int) Math.round(x));
        return String.format(Locale.US, "%.1f", x);
    }

    private TextView center(String text, int size, int color) {
        TextView tv = text(text, size, color, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        hud.addView(tv);
        return tv;
    }

    private TextView section(String text) {
        TextView tv = text(text, 14, Color.WHITE, Typeface.BOLD);
        tv.setPadding(0, 20, 0, 8);
        return tv;
    }

    private TextView cardText(String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(20, 16, 20, 16);
        card.setBackground(roundBg(Color.argb(205, 18, 18, 18)));
        TextView h = text(title, 15, Color.WHITE, Typeface.BOLD);
        TextView b = text(body, 15, Color.rgb(225, 225, 225), Typeface.NORMAL);
        card.addView(h);
        card.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 12, 0, 8);
        hud.addView(card, lp);
        return b;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.LTGRAY);
        e.setTextSize(12);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 88, 1);
        lp.setMargins(4, 0, 4, 0);
        e.setLayoutParams(lp);
        e.setBackground(roundBg(Color.argb(190, 30, 30, 30)));
        e.setPadding(12, 0, 12, 0);
        return e;
    }

    private TextView text(String s, int size, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, style);
        tv.setPadding(0, 5, 0, 5);
        return tv;
    }

    private Button largeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(roundBg(Color.rgb(45, 120, 245)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 112);
        lp.setMargins(0, 8, 0, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button smallButton(String label) {
        Button b = largeButton(label);
        b.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 90, 1);
        lp.setMargins(4, 4, 4, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable roundBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(22f);
        g.setStroke(1, Color.rgb(65, 65, 65));
        return g;
    }
}
