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
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OmegaActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION = 77;
    private static final double MPS_TO_MPH = 2.2369362921;
    private static final double MPS2_TO_FTPS2 = 3.280839895;
    private static final double KG_TO_LB = 2.2046226218;
    private static final double J_TO_FTLB = 0.7375621493;
    private static final double KGMPS_TO_LBMFTPS = 7.233013851;
    private static final double N_TO_LBF = 0.2248089431;

    private enum LabMode { QUICK, VELOCITY, POWER, COMBO, ENDURANCE }
    private enum Stance { ORTHODOX, SOUTHPAW }

    private FrameLayout root;
    private PreviewView previewView;
    private LinearLayout hud;
    private TextView statusText, liveText, timerText, latestText, calibrationText, fpsText, capabilityText, modeText;
    private EditText weightInput, heightInput, armInput, shoulderInput;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService cameraExecutor;
    private PoseDetector poseDetector;
    private SharedPreferences prefs;

    private boolean processing = false;
    private boolean activeTest = false;
    private boolean calibrating = false;
    private int selectedSeconds = 10;
    private int sensitivity = 2;
    private LabMode labMode = LabMode.VELOCITY;
    private Stance stance = Stance.ORTHODOX;
    private long testEndUptimeMs = 0;
    private long testStartUptimeMs = 0;
    private long poseFrames = 0;
    private long fpsWindowStartMs = 0;
    private long fpsWindowFrames = 0;
    private double measuredFps = 0.0;

    private double bodyWeightLb = 150.0;
    private double heightIn = 67.0;
    private double armLengthIn = 27.0;
    private double shoulderWidthIn = 17.0;
    private double pxPerMeter = 0.0;
    private double calibrationQuality = 0.0;
    private final ArrayList<Double> calibrationSamples = new ArrayList<>();

    private final Map<Integer, MotionState> motions = new HashMap<>();
    private final Map<Integer, StrikeTracker> strikeTracks = new HashMap<>();
    private final ArrayList<StrikeResult> currentResults = new ArrayList<>();

    private PointF lastShoulderCenter = null;
    private PointF lastHipCenter = null;
    private long lastBodyTsNs = 0;
    private double shoulderCenterSpeed = 0.0;
    private double hipCenterSpeed = 0.0;
    private long shoulderPeakTs = 0;
    private long hipPeakTs = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("strikevision_omega_v3", MODE_PRIVATE);
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
            denied.setText("Camera permission is required for StrikeVision Ω.");
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
        scroll.setBackgroundColor(Color.argb(74, 0, 0, 0));
        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(20, 34, 20, 28);
        scroll.addView(hud, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        center("STRIKEVISION Ω", 29, Color.WHITE);
        center("Local Combat Telemetry Lab", 14, Color.rgb(185, 255, 45));
        statusText = center("Starting front camera...", 13, Color.rgb(185, 255, 45));
        capabilityText = center("Camera: probing capabilities...", 11, Color.LTGRAY);
        fpsText = center("Pose: 0 fps", 11, Color.LTGRAY);
        modeText = center(modeLabel(), 13, Color.rgb(110, 190, 255));
        timerText = center("Ready", 20, Color.WHITE);
        liveText = center("Live weapon speed: 0.0 mph", 18, Color.rgb(80, 165, 255));
        calibrationText = center(calibrationLabel(), 12, Color.LTGRAY);
        latestText = cardText("Latest measured strike", "No strike yet");

        hud.addView(section("Fighter calibration profile"));
        LinearLayout p1 = new LinearLayout(this);
        p1.setOrientation(LinearLayout.HORIZONTAL);
        weightInput = field("Weight lb", fmt(bodyWeightLb));
        heightInput = field("Height in", fmt(heightIn));
        p1.addView(weightInput);
        p1.addView(heightInput);
        hud.addView(p1);

        LinearLayout p2 = new LinearLayout(this);
        p2.setOrientation(LinearLayout.HORIZONTAL);
        armInput = field("Shoulder→wrist in", fmt(armLengthIn));
        shoulderInput = field("Shoulder width in", fmt(shoulderWidthIn));
        p2.addView(armInput);
        p2.addView(shoulderInput);
        hud.addView(p2);

        LinearLayout calRow = new LinearLayout(this);
        calRow.setOrientation(LinearLayout.HORIZONTAL);
        Button calibrate = smallButton("Calibrate scale");
        Button resetCal = smallButton("Reset scale");
        calibrate.setOnClickListener(v -> beginCalibration());
        resetCal.setOnClickListener(v -> resetCalibration());
        calRow.addView(calibrate);
        calRow.addView(resetCal);
        hud.addView(calRow);

        hud.addView(section("Ω Lab mode"));
        LinearLayout modeRow1 = new LinearLayout(this);
        modeRow1.setOrientation(LinearLayout.HORIZONTAL);
        modeRow1.addView(modeButton("Quick", LabMode.QUICK));
        modeRow1.addView(modeButton("Velocity", LabMode.VELOCITY));
        modeRow1.addView(modeButton("Power", LabMode.POWER));
        hud.addView(modeRow1);
        LinearLayout modeRow2 = new LinearLayout(this);
        modeRow2.setOrientation(LinearLayout.HORIZONTAL);
        modeRow2.addView(modeButton("Combo", LabMode.COMBO));
        modeRow2.addView(modeButton("Endurance", LabMode.ENDURANCE));
        hud.addView(modeRow2);

        hud.addView(section("Stance"));
        LinearLayout stanceRow = new LinearLayout(this);
        stanceRow.setOrientation(LinearLayout.HORIZONTAL);
        Button orthodox = smallButton("Orthodox");
        Button southpaw = smallButton("Southpaw");
        orthodox.setOnClickListener(v -> setStance(Stance.ORTHODOX));
        southpaw.setOnClickListener(v -> setStance(Stance.SOUTHPAW));
        stanceRow.addView(orthodox);
        stanceRow.addView(southpaw);
        hud.addView(stanceRow);

        hud.addView(section("Test length"));
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int seconds : new int[]{5, 10, 15, 30}) {
            Button b = smallButton(seconds + "s");
            b.setOnClickListener(v -> {
                selectedSeconds = seconds;
                statusText.setText(seconds + " second window selected");
            });
            timeRow.addView(b);
        }
        hud.addView(timeRow);

        hud.addView(section("Detection gate"));
        LinearLayout sensRow = new LinearLayout(this);
        sensRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Strict", "Balanced", "Sensitive"};
        for (int i = 0; i < names.length; i++) {
            final int s = i + 1;
            Button b = smallButton(names[i]);
            b.setOnClickListener(v -> {
                sensitivity = s;
                statusText.setText(names[s - 1] + " strike gate");
            });
            sensRow.addView(b);
        }
        hud.addView(sensRow);

        View spacer = new View(this);
        hud.addView(spacer, new LinearLayout.LayoutParams(1, 36));
        Button start = largeButton("START 5-COUNT");
        start.setOnClickListener(v -> startCountdown());
        hud.addView(start);

        LinearLayout bottom1 = new LinearLayout(this);
        bottom1.setOrientation(LinearLayout.HORIZONTAL);
        Button history = smallButton("History");
        Button pbs = smallButton("Personal bests");
        history.setOnClickListener(v -> showHistory());
        pbs.setOnClickListener(v -> showPersonalBests());
        bottom1.addView(history);
        bottom1.addView(pbs);
        hud.addView(bottom1);

        Button profile = largeButton("Save fighter profile");
        profile.setTextSize(13);
        profile.setOnClickListener(v -> {
            if (readProfileInputs()) {
                saveProfile();
                statusText.setText("Fighter profile saved locally");
            }
        });
        hud.addView(profile);

        center("Measured: camera trajectory + timing. Derived: acceleration, momentum and kinetic energy potential. Estimated: effective striking mass and force window. Side view gives the strongest speed geometry.", 10, Color.LTGRAY);

        queryCameraCapabilities();
        bindCamera();
        startUiLoop();
    }

    private Button modeButton(String label, LabMode mode) {
        Button b = smallButton(label);
        b.setOnClickListener(v -> {
            labMode = mode;
            modeText.setText(modeLabel());
            statusText.setText(label + " mode armed");
        });
        return b;
    }

    private void setStance(Stance s) {
        stance = s;
        statusText.setText(s == Stance.ORTHODOX ? "Orthodox stance" : "Southpaw stance");
        saveProfile();
    }

    private String modeLabel() {
        switch (labMode) {
            case QUICK: return "Mode: Quick Strike";
            case POWER: return "Mode: Power Lab";
            case COMBO: return "Mode: Combo Lab";
            case ENDURANCE: return "Mode: Endurance";
            default: return "Mode: Velocity Test";
        }
    }

    private void queryCameraCapabilities() {
        cameraExecutor.execute(() -> {
            String label = "Camera: capability query unavailable";
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                int aeMax = 0;
                int hsMax = 0;
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics c = cm.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
                    Range<Integer>[] ae = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                    if (ae != null) {
                        for (Range<Integer> r : ae) aeMax = Math.max(aeMax, r.getUpper());
                    }
                    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        try {
                            Range<Integer>[] high = map.getHighSpeedVideoFpsRanges();
                            if (high != null) for (Range<Integer> r : high) hsMax = Math.max(hsMax, r.getUpper());
                        } catch (Throwable ignored) {}
                    }
                }
                label = String.format(Locale.US, "Front camera: normal AE up to %d fps | high-speed capability up to %d fps | pose engine reports actual below", aeMax, hsMax);
            } catch (Throwable ignored) {}
            final String out = label;
            ui.post(() -> { if (capabilityText != null) capabilityText.setText(out); });
        });
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
                statusText.setText("Pose live. Calibrate, then throw side-on for strongest mph accuracy.");
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

        int[] trackedIds = {
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
                PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
        };
        for (int id : trackedIds) {
            PoseLandmark lm = pose.getPoseLandmark(id);
            if (!good(lm)) continue;
            MotionState m = motions.get(id);
            if (m == null) {
                m = new MotionState();
                motions.put(id, m);
            }
            m.update(lm.getPosition(), torso, tsNs, currentScale(), lm.getInFrameLikelihood());
        }

        double fastest = 0.0;
        String fastestName = "";
        int[] weapons = { PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE };
        for (int id : weapons) {
            MotionState weapon = motions.get(id);
            if (weapon == null) continue;
            StrikeTracker tr = strikeTracks.get(id);
            if (tr == null) {
                tr = new StrikeTracker(id);
                strikeTracks.put(id, tr);
            }
            MotionState support = motions.get(supportJointId(id));
            StrikeResult emitted = tr.update(weapon, support, tsNs);
            if (weapon.speedMps > fastest) {
                fastest = weapon.speedMps;
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

    private void updateBodySpeeds(PointF shoulderCenter, PointF hipCenter, long tsNs) {
        if (lastBodyTsNs > 0) {
            double dt = (tsNs - lastBodyTsNs) / 1_000_000_000.0;
            if (dt >= 0.008 && dt <= 0.20 && currentScale() > 1.0) {
                if (shoulderCenter != null && lastShoulderCenter != null) {
                    double v = dist(shoulderCenter, lastShoulderCenter) / currentScale() / dt;
                    shoulderCenterSpeed = shoulderCenterSpeed * 0.55 + v * 0.45;
                    if (v >= shoulderCenterSpeed) shoulderPeakTs = tsNs;
                }
                if (hipCenter != null && lastHipCenter != null) {
                    double v = dist(hipCenter, lastHipCenter) / currentScale() / dt;
                    hipCenterSpeed = hipCenterSpeed * 0.55 + v * 0.45;
                    if (v >= hipCenterSpeed) hipPeakTs = tsNs;
                }
            }
        }
        if (shoulderCenter != null) lastShoulderCenter = new PointF(shoulderCenter.x, shoulderCenter.y);
        if (hipCenter != null) lastHipCenter = new PointF(hipCenter.x, hipCenter.y);
        lastBodyTsNs = tsNs;
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
        if (calibrationSamples.size() >= 18) {
            Collections.sort(calibrationSamples);
            double median = calibrationSamples.get(calibrationSamples.size() / 2);
            double spread = percentile(calibrationSamples, 0.85) - percentile(calibrationSamples, 0.15);
            double meters = armLengthIn * 0.0254;
            pxPerMeter = median / Math.max(0.30, meters);
            calibrationQuality = clamp(1.0 - spread / Math.max(1.0, median) * 1.75, 0.62, 0.98);
            prefs.edit().putFloat("pxPerMeter", (float) pxPerMeter).putFloat("calQuality", (float) calibrationQuality).apply();
            calibrating = false;
            ui.post(() -> {
                calibrationText.setText(calibrationLabel());
                statusText.setText("Scale calibrated. Keep the same phone position for this session.");
            });
        }
    }

    private void resetCalibration() {
        pxPerMeter = 0.0;
        calibrationQuality = 0.0;
        calibrationSamples.clear();
        prefs.edit().remove("pxPerMeter").remove("calQuality").apply();
        calibrationText.setText(calibrationLabel());
        statusText.setText("Scale reset. Recalibrate before precision testing.");
    }

    private void updateFallbackScale(PoseLandmark ls, PoseLandmark rs) {
        if (!good(ls) || !good(rs)) return;
        double shoulderPx = dist(ls.getPosition(), rs.getPosition());
        if (shoulderPx < 30) return;
        double meters = shoulderWidthIn * 0.0254;
        pxPerMeter = shoulderPx / Math.max(0.20, meters);
        calibrationQuality = 0.40;
    }

    private double currentScale() {
        return pxPerMeter > 1.0 ? pxPerMeter : 500.0;
    }

    private void startCountdown() {
        if (activeTest) return;
        if (!readProfileInputs()) return;
        saveProfile();
        currentResults.clear();
        resetMotionState();
        timerText.setText("5");
        for (int i = 4; i >= 1; i--) {
            final int n = i;
            ui.postDelayed(() -> timerText.setText(String.valueOf(n)), (5L - i) * 1000L);
        }
        ui.postDelayed(() -> {
            activeTest = true;
            testStartUptimeMs = SystemClock.uptimeMillis();
            testEndUptimeMs = testStartUptimeMs + selectedSeconds * 1000L;
            timerText.setText("GO");
            statusText.setText(labMode == LabMode.POWER ? "Measuring speed + modeled impact mechanics" : "Measuring strike telemetry");
        }, 5000L);
        ui.postDelayed(this::finishTest, 5000L + selectedSeconds * 1000L + 300L);
    }

    private void finishTest() {
        if (!activeTest) return;
        activeTest = false;
        for (StrikeTracker tr : strikeTracks.values()) {
            StrikeResult tail = tr.forceFinish(System.nanoTime());
            if (tail != null) onStrike(tail);
        }
        timerText.setText("Complete");
        if (currentResults.isEmpty()) {
            statusText.setText("No clean strike burst detected.");
            new AlertDialog.Builder(this)
                    .setTitle("No accepted strikes")
                    .setMessage("Keep the phone fixed, stay side-on, keep the striking limb visible, and try Sensitive if the gate is rejecting real strikes.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        SessionSummary s = summarizeSession();
        statusText.setText(currentResults.size() + " strikes stored locally");
        new AlertDialog.Builder(this)
                .setTitle("StrikeVision Ω result")
                .setMessage(summaryMessage(s))
                .setPositiveButton("Run again", (d, w) -> timerText.setText("Ready"))
                .setNegativeButton("History", (d, w) -> showHistory())
                .show();
    }

    private String summaryMessage(SessionSummary s) {
        String core = String.format(Locale.US,
                "%d accepted strikes\nFastest: %.1f mph\nAverage peak: %.1f mph\nVelocity retention: %.0f%%\nAverage chain score: %.0f/100\nAverage confidence: %.0f%%\nLeft/right asymmetry: %.0f%%\nNew personal bests: %d",
                s.count, s.fastestMph, s.averageMph, s.velocityRetentionPct, s.avgChainScore, s.avgConfidence * 100.0, s.asymmetryPct, s.newPbCount);
        if (labMode == LabMode.POWER) {
            core += String.format(Locale.US, "\nHighest kinetic-energy potential: %.1f ft·lbf\nHighest momentum: %.1f lbm·ft/s", s.highestEnergyFtLb, s.highestMomentum);
        }
        if (labMode == LabMode.COMBO) {
            core += String.format(Locale.US, "\nAverage strike-to-strike transition: %.0f ms", s.avgTransitionMs);
        }
        if (labMode == LabMode.ENDURANCE) {
            core += String.format(Locale.US, "\nFirst-half average: %.1f mph\nSecond-half average: %.1f mph", s.firstHalfMph, s.secondHalfMph);
        }
        return core + "\n\nΩ read: " + s.insight;
    }

    private SessionSummary summarizeSession() {
        SessionSummary s = new SessionSummary();
        s.count = currentResults.size();
        double total = 0, chain = 0, conf = 0, left = 0, right = 0;
        int leftN = 0, rightN = 0;
        long prev = -1;
        double transitionTotal = 0;
        int transitions = 0;
        for (StrikeResult r : currentResults) {
            s.fastestMph = Math.max(s.fastestMph, r.peakMph);
            s.highestEnergyFtLb = Math.max(s.highestEnergyFtLb, r.energyFtLb);
            s.highestMomentum = Math.max(s.highestMomentum, r.momentumImperial);
            total += r.peakMph;
            chain += r.chainScore;
            conf += r.confidence;
            if (r.isLeft) { left += r.peakMph; leftN++; } else { right += r.peakMph; rightN++; }
            if (r.newPb) s.newPbCount++;
            if (prev >= 0) { transitionTotal += Math.max(0, r.elapsedMs - prev); transitions++; }
            prev = r.elapsedMs;
        }
        s.averageMph = total / s.count;
        s.avgChainScore = chain / s.count;
        s.avgConfidence = conf / s.count;
        s.avgTransitionMs = transitions == 0 ? 0 : transitionTotal / transitions;
        int half = Math.max(1, s.count / 2);
        double first = 0, second = 0;
        int firstN = 0, secondN = 0;
        for (int i = 0; i < s.count; i++) {
            if (i < half) { first += currentResults.get(i).peakMph; firstN++; }
            else { second += currentResults.get(i).peakMph; secondN++; }
        }
        s.firstHalfMph = firstN == 0 ? s.averageMph : first / firstN;
        s.secondHalfMph = secondN == 0 ? s.firstHalfMph : second / secondN;
        s.velocityRetentionPct = s.firstHalfMph <= 0.1 ? 100 : clamp(s.secondHalfMph / s.firstHalfMph * 100.0, 0, 160);
        if (leftN > 0 && rightN > 0) {
            double la = left / leftN, ra = right / rightN;
            s.asymmetryPct = Math.abs(la - ra) / Math.max(la, ra) * 100.0;
        }
        if (s.avgConfidence < 0.55) s.insight = "measurement confidence is the limiter. Recalibration and a cleaner side view should sharpen the numbers.";
        else if (s.velocityRetentionPct < 84) s.insight = "speed is decaying before the round ends. The endurance signal is stronger than the raw maximum.";
        else if (s.avgChainScore < 62) s.insight = "limb speed is outrunning the kinetic chain. The next gain is likely sequencing, not simply moving the hand or foot faster.";
        else if (s.asymmetryPct > 22) s.insight = "the left/right engines are separating. Treat them as two different mechanical signatures instead of one average.";
        else s.insight = "the session is mechanically coherent. Chase the cleanest repeatable peak, not a single noisy spike.";
        return s;
    }

    private void onStrike(StrikeResult r) {
        if (!activeTest && testStartUptimeMs == 0) return;
        r.elapsedMs = Math.max(0, SystemClock.uptimeMillis() - testStartUptimeMs);
        r.newPb = recordPersonalBest(r);
        currentResults.add(r);
        saveHistory(r);
        String pb = r.newPb ? "\n★ NEW PERSONAL BEST" : "";
        String detail = String.format(Locale.US,
                "%s%s\nPeak %.1f mph | avg %.1f mph | recoil %.1f mph\nTime-to-peak %.0f ms | burst %.0f ms | extension %.1f in\nPeak accel %.0f ft/s² | path %.2f ft\nChain score %.0f/100 | %s\nModeled effective mass %.1f lb\nMomentum %.1f lbm·ft/s | kinetic-energy potential %.1f ft·lbf\nEstimated force window %.0f–%.0f lbf\nMeasurement confidence %.0f%% (%s)",
                r.technique, pb,
                r.peakMph, r.avgMph, r.recoilMph,
                r.timeToPeakMs, r.durationMs, r.extensionIn,
                r.peakAccelFt, r.pathFt,
                r.chainScore, r.leakLabel,
                r.effectiveMassLb,
                r.momentumImperial, r.energyFtLb,
                r.forceLowLbf, r.forceHighLbf,
                r.confidence * 100.0, gradeFor(r.confidence));
        ui.post(() -> latestText.setText(detail));
    }

    private boolean recordPersonalBest(StrikeResult r) {
        if (r.confidence < 0.50) return false;
        try {
            JSONObject pbs = new JSONObject(prefs.getString("pbs", "{}"));
            String key = (r.technique + "|" + stance.name()).replace(' ', '_');
            double old = pbs.optDouble(key, 0.0);
            if (r.peakMph > old) {
                pbs.put(key, r.peakMph);
                prefs.edit().putString("pbs", pbs.toString()).apply();
                return old > 0.0 || r.peakMph > detectionThreshold(r.weaponId) * MPS_TO_MPH;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveHistory(StrikeResult r) {
        try {
            JSONArray arr;
            try { arr = new JSONArray(prefs.getString("history", "[]")); }
            catch (Throwable t) { arr = new JSONArray(); }
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("technique", r.technique);
            o.put("mph", r.peakMph);
            o.put("avgMph", r.avgMph);
            o.put("recoilMph", r.recoilMph);
            o.put("timeToPeakMs", r.timeToPeakMs);
            o.put("durationMs", r.durationMs);
            o.put("extensionIn", r.extensionIn);
            o.put("chain", r.chainScore);
            o.put("leak", r.leakLabel);
            o.put("energyFtLb", r.energyFtLb);
            o.put("momentum", r.momentumImperial);
            o.put("forceLow", r.forceLowLbf);
            o.put("forceHigh", r.forceHighLbf);
            o.put("confidence", r.confidence);
            o.put("mode", labMode.name());
            arr.put(o);
            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, arr.length() - 300);
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
            int start = Math.max(0, arr.length() - 40);
            for (int i = arr.length() - 1; i >= start; i--) {
                JSONObject o = arr.getJSONObject(i);
                sb.append('#').append(i + 1).append(' ').append(o.optString("technique", "Strike")).append('\n')
                        .append(String.format(Locale.US, "%.1f mph | chain %.0f | %.0f%% conf | %.1f ft·lbf\n%s\n\n",
                                o.optDouble("mph"), o.optDouble("chain"), o.optDouble("confidence") * 100.0,
                                o.optDouble("energyFtLb"), o.optString("leak", "")));
            }
            new AlertDialog.Builder(this)
                    .setTitle("Recent Ω telemetry")
                    .setMessage(sb.toString())
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Clear history", (d, w) -> {
                        prefs.edit().remove("history").apply();
                        statusText.setText("History cleared");
                    }).show();
        } catch (Throwable t) {
            new AlertDialog.Builder(this).setTitle("History").setMessage("History could not be read.").setPositiveButton("OK", null).show();
        }
    }

    private void showPersonalBests() {
        try {
            JSONObject pbs = new JSONObject(prefs.getString("pbs", "{}"));
            ArrayList<String> lines = new ArrayList<>();
            Iterator<String> keys = pbs.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                lines.add(k.replace('_', ' ') + "  " + String.format(Locale.US, "%.1f mph", pbs.optDouble(k)));
            }
            Collections.sort(lines);
            String msg = lines.isEmpty() ? "No personal bests yet." : android.text.TextUtils.join("\n", lines);
            new AlertDialog.Builder(this).setTitle("Personal best map").setMessage(msg).setPositiveButton("Close", null).show();
        } catch (Throwable t) {
            new AlertDialog.Builder(this).setTitle("Personal bests").setMessage("Personal-best data could not be read.").setPositiveButton("OK", null).show();
        }
    }

    private void resetMotionState() {
        motions.clear();
        strikeTracks.clear();
        lastShoulderCenter = null;
        lastHipCenter = null;
        lastBodyTsNs = 0;
        shoulderCenterSpeed = 0;
        hipCenterSpeed = 0;
        shoulderPeakTs = 0;
        hipPeakTs = 0;
        testStartUptimeMs = 0;
    }

    private void updateFps() {
        long now = SystemClock.uptimeMillis();
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
                if (fpsText != null) fpsText.setText(String.format(Locale.US, "Pose telemetry: %.1f fps | processed frames: %d", measuredFps, poseFrames));
                if (activeTest && timerText != null) {
                    long left = Math.max(0, testEndUptimeMs - SystemClock.uptimeMillis());
                    timerText.setText(String.format(Locale.US, "%.1fs", left / 1000.0));
                }
                if (calibrationText != null) calibrationText.setText(calibrationLabel());
                ui.postDelayed(this, 250);
            }
        }, 250);
    }

    private String calibrationLabel() {
        if (pxPerMeter > 1.0 && calibrationQuality >= 0.60) {
            return String.format(Locale.US, "Scale: calibrated | geometry confidence %.0f%%", calibrationQuality * 100.0);
        }
        if (pxPerMeter > 1.0) return "Scale: approximate shoulder-width fallback";
        return "Scale: not calibrated";
    }

    private boolean readProfileInputs() {
        try {
            bodyWeightLb = Double.parseDouble(weightInput.getText().toString().trim());
            heightIn = Double.parseDouble(heightInput.getText().toString().trim());
            armLengthIn = Double.parseDouble(armInput.getText().toString().trim());
            shoulderWidthIn = Double.parseDouble(shoulderInput.getText().toString().trim());
            if (bodyWeightLb < 60 || bodyWeightLb > 500 || heightIn < 48 || heightIn > 90 || armLengthIn < 15 || armLengthIn > 45 || shoulderWidthIn < 8 || shoulderWidthIn > 30) throw new IllegalArgumentException();
            return true;
        } catch (Throwable t) {
            new AlertDialog.Builder(this)
                    .setTitle("Check fighter profile")
                    .setMessage("Use pounds and inches. Example: 150 lb, 67 in tall, 27 in shoulder-to-wrist, 17 in shoulder width.")
                    .setPositiveButton("OK", null).show();
            return false;
        }
    }

    private void loadProfile() {
        bodyWeightLb = prefs.getFloat("weightLb", 150f);
        heightIn = prefs.getFloat("heightIn", 67f);
        armLengthIn = prefs.getFloat("armIn", 27f);
        shoulderWidthIn = prefs.getFloat("shoulderIn", 17f);
        pxPerMeter = prefs.getFloat("pxPerMeter", 0f);
        calibrationQuality = prefs.getFloat("calQuality", 0f);
        stance = "SOUTHPAW".equals(prefs.getString("stance", "ORTHODOX")) ? Stance.SOUTHPAW : Stance.ORTHODOX;
    }

    private void saveProfile() {
        prefs.edit()
                .putFloat("weightLb", (float) bodyWeightLb)
                .putFloat("heightIn", (float) heightIn)
                .putFloat("armIn", (float) armLengthIn)
                .putFloat("shoulderIn", (float) shoulderWidthIn)
                .putString("stance", stance.name())
                .apply();
    }

    private class MotionState {
        PointF smoothed;
        PointF relative;
        long tsNs;
        double speedMps;
        double prevSpeedMps;
        double accelMps2;
        float confidence;
        final double[] velocityWindow = new double[3];
        int velocityCount = 0;
        int velocityIndex = 0;

        void update(PointF raw, PointF torso, long timeNs, double scalePxM, float conf) {
            confidence = conf;
            if (smoothed == null) {
                smoothed = new PointF(raw.x, raw.y);
                relative = new PointF(raw.x - torso.x, raw.y - torso.y);
                tsNs = timeNs;
                return;
            }
            smoothed = new PointF((float)(smoothed.x * 0.30 + raw.x * 0.70), (float)(smoothed.y * 0.30 + raw.y * 0.70));
            PointF next = new PointF(smoothed.x - torso.x, smoothed.y - torso.y);
            double dt = (timeNs - tsNs) / 1_000_000_000.0;
            if (dt < 0.008 || dt > 0.20) {
                relative = next;
                tsNs = timeNs;
                speedMps = 0;
                accelMps2 = 0;
                return;
            }
            double instant = dist(next, relative) / Math.max(1.0, scalePxM) / dt;
            velocityWindow[velocityIndex] = instant;
            velocityIndex = (velocityIndex + 1) % 3;
            velocityCount = Math.min(3, velocityCount + 1);
            double robust;
            if (velocityCount == 1) robust = velocityWindow[0];
            else if (velocityCount == 2) robust = (velocityWindow[0] + velocityWindow[1]) / 2.0;
            else {
                double[] c = Arrays.copyOf(velocityWindow, 3);
                Arrays.sort(c);
                robust = c[1];
            }
            prevSpeedMps = speedMps;
            speedMps = prevSpeedMps * 0.28 + robust * 0.72;
            accelMps2 = (speedMps - prevSpeedMps) / dt;
            relative = next;
            tsNs = timeNs;
        }
    }

    private class StrikeTracker {
        final int id;
        boolean burst = false;
        long startTs = 0, peakTs = 0, lastFastTs = 0, lastEmitTs = 0;
        PointF startPoint = null, peakPoint = null, prevPoint = null;
        double peakMps = 0, peakAccel = 0, sumMps = 0, recoilMaxMps = 0, pathM = 0;
        int sampleCount = 0;
        double peakLandmarkConfidence = 0;
        double maxSupportMps = 0, maxShoulderMps = 0, maxHipMps = 0;
        long supportPeakTs = 0, localShoulderPeakTs = 0, localHipPeakTs = 0;

        StrikeTracker(int id) { this.id = id; }

        StrikeResult update(MotionState weapon, MotionState support, long tsNs) {
            if (!activeTest) return null;
            double threshold = detectionThreshold(id);
            if (!burst) {
                double minAccel = isHand(id) ? 4.5 : 6.0;
                if (weapon.speedMps >= threshold && weapon.accelMps2 >= minAccel && tsNs - lastEmitTs > 220_000_000L) {
                    burst = true;
                    startTs = tsNs;
                    peakTs = tsNs;
                    lastFastTs = tsNs;
                    startPoint = copy(weapon.relative);
                    peakPoint = copy(weapon.relative);
                    prevPoint = copy(weapon.relative);
                    peakMps = weapon.speedMps;
                    peakAccel = Math.max(0, weapon.accelMps2);
                    sumMps = weapon.speedMps;
                    sampleCount = 1;
                    peakLandmarkConfidence = weapon.confidence;
                    sampleChain(support, tsNs);
                }
                return null;
            }

            sampleCount++;
            sumMps += weapon.speedMps;
            peakAccel = Math.max(peakAccel, Math.max(0, weapon.accelMps2));
            if (prevPoint != null && weapon.relative != null) pathM += dist(prevPoint, weapon.relative) / currentScale();
            prevPoint = copy(weapon.relative);
            if (weapon.speedMps > peakMps) {
                peakMps = weapon.speedMps;
                peakTs = tsNs;
                peakPoint = copy(weapon.relative);
                peakLandmarkConfidence = weapon.confidence;
            } else if (tsNs > peakTs) {
                recoilMaxMps = Math.max(recoilMaxMps, weapon.speedMps);
            }
            sampleChain(support, tsNs);
            if (weapon.speedMps >= threshold * 0.70) lastFastTs = tsNs;

            boolean slowed = tsNs - lastFastTs > 125_000_000L;
            boolean timeout = tsNs - startTs > 900_000_000L;
            if (slowed || timeout) return finish(tsNs);
            return null;
        }

        private void sampleChain(MotionState support, long tsNs) {
            if (support != null && support.speedMps > maxSupportMps) { maxSupportMps = support.speedMps; supportPeakTs = tsNs; }
            if (shoulderCenterSpeed > maxShoulderMps) { maxShoulderMps = shoulderCenterSpeed; localShoulderPeakTs = shoulderPeakTs == 0 ? tsNs : shoulderPeakTs; }
            if (hipCenterSpeed > maxHipMps) { maxHipMps = hipCenterSpeed; localHipPeakTs = hipPeakTs == 0 ? tsNs : hipPeakTs; }
        }

        StrikeResult forceFinish(long tsNs) {
            return burst ? finish(tsNs) : null;
        }

        private StrikeResult finish(long tsNs) {
            burst = false;
            lastEmitTs = tsNs;
            if (peakMps < detectionThreshold(id) || peakMps > 35.0 || sampleCount < 2) {
                resetBurst();
                return null;
            }
            StrikeResult r = new StrikeResult();
            r.weaponId = id;
            r.isLeft = isLeft(id);
            r.technique = classifyTechnique(id, startPoint, peakPoint);
            r.peakMph = peakMps * MPS_TO_MPH;
            r.avgMph = (sumMps / Math.max(1, sampleCount)) * MPS_TO_MPH;
            r.recoilMph = recoilMaxMps * MPS_TO_MPH;
            r.peakAccelFt = peakAccel * MPS2_TO_FTPS2;
            r.timeToPeakMs = Math.max(0, (peakTs - startTs) / 1_000_000.0);
            r.durationMs = Math.max(1, (tsNs - startTs) / 1_000_000.0);
            r.pathFt = pathM * 3.280839895;
            r.extensionIn = startPoint == null || peakPoint == null ? 0 : dist(startPoint, peakPoint) / currentScale() * 39.37007874;

            double supportRatio = clamp(maxSupportMps / Math.max(0.1, peakMps), 0, 1.0);
            double shoulderRatio = clamp(maxShoulderMps / Math.max(0.1, peakMps), 0, 0.80);
            double hipRatio = clamp(maxHipMps / Math.max(0.1, peakMps), 0, 0.70);
            int ordered = 0;
            if (localHipPeakTs > 0 && localShoulderPeakTs > 0 && localHipPeakTs <= localShoulderPeakTs) ordered++;
            if (localShoulderPeakTs > 0 && supportPeakTs > 0 && localShoulderPeakTs <= supportPeakTs) ordered++;
            if (supportPeakTs > 0 && peakTs > 0 && supportPeakTs <= peakTs) ordered++;
            double sequence = ordered / 3.0;
            double chainNorm;
            if (isHand(id)) chainNorm = 0.34 * supportRatio + 0.28 * shoulderRatio / 0.80 + 0.22 * hipRatio / 0.70 + 0.16 * sequence;
            else chainNorm = 0.42 * supportRatio + 0.10 * shoulderRatio / 0.80 + 0.32 * hipRatio / 0.70 + 0.16 * sequence;
            r.chainScore = clamp(chainNorm * 100.0, 0, 100);
            r.leakLabel = leakLabel(id, supportRatio, shoulderRatio, hipRatio, sequence);

            double kg = bodyWeightLb / KG_TO_LB;
            double baseFraction = isHand(id) ? 0.055 : 0.145;
            double extraFraction = isHand(id) ? 0.040 : 0.115;
            double effectiveMassKg = kg * (baseFraction + extraFraction * r.chainScore / 100.0);
            effectiveMassKg = Math.min(effectiveMassKg, kg * (isHand(id) ? 0.11 : 0.29));
            double momentum = effectiveMassKg * peakMps;
            double energyJ = 0.5 * effectiveMassKg * peakMps * peakMps;
            double forceLowN = momentum / 0.032;
            double forceHighN = momentum / 0.012;
            r.effectiveMassLb = effectiveMassKg * KG_TO_LB;
            r.momentumImperial = momentum * KGMPS_TO_LBMFTPS;
            r.energyFtLb = energyJ * J_TO_FTLB;
            r.forceLowLbf = forceLowN * N_TO_LBF;
            r.forceHighLbf = forceHighN * N_TO_LBF;

            double fpsQuality = clamp(measuredFps / 30.0, 0.38, 1.0);
            double cal = Math.max(0.35, calibrationQuality);
            double speedSanity = peakMps > 1.0 && peakMps < 25.0 ? 1.0 : 0.78;
            double pathQuality = r.extensionIn >= 2.0 ? 1.0 : 0.72;
            r.confidence = clamp(peakLandmarkConfidence * cal * fpsQuality * speedSanity * pathQuality, 0.12, 0.98);
            if (calibrationQuality < 0.60) r.confidence = Math.min(r.confidence, 0.68);

            resetBurst();
            return r;
        }

        private void resetBurst() {
            startTs = peakTs = lastFastTs = 0;
            startPoint = peakPoint = prevPoint = null;
            peakMps = peakAccel = sumMps = recoilMaxMps = pathM = 0;
            sampleCount = 0;
            peakLandmarkConfidence = 0;
            maxSupportMps = maxShoulderMps = maxHipMps = 0;
            supportPeakTs = localShoulderPeakTs = localHipPeakTs = 0;
        }
    }

    private String classifyTechnique(int id, PointF start, PointF peak) {
        String side = isLeft(id) ? "Left" : "Right";
        if (start == null || peak == null) return side + (isHand(id) ? " hand strike" : " kick");
        double dx = peak.x - start.x;
        double dy = peak.y - start.y;
        double ax = Math.abs(dx), ay = Math.abs(dy);
        if (isHand(id)) {
            boolean lead = (stance == Stance.ORTHODOX && isLeft(id)) || (stance == Stance.SOUTHPAW && !isLeft(id));
            String role = lead ? "Lead" : "Rear";
            if (ax > ay * 1.45) return role + (lead ? " jab" : " cross");
            if (dy < 0 && ay > ax * 0.70) return role + " uppercut";
            if (dy > 0 && ay > ax * 0.65) return role + " overhand";
            return role + " hook";
        }
        if (ax > ay * 1.55) return side + " round/side kick trajectory";
        if (dy < 0 && ay > ax * 0.75) return side + " rising/front kick trajectory";
        return side + " kick";
    }

    private String leakLabel(int id, double support, double shoulder, double hip, double sequence) {
        if (support < 0.20) return isHand(id) ? "support elbow lag" : "support knee lag";
        if (hip < 0.08) return "low hip contribution";
        if (isHand(id) && shoulder < 0.08) return "low shoulder transfer";
        if (sequence < 0.50) return "segment timing overlap";
        return "clean kinetic cascade";
    }

    private double detectionThreshold(int id) {
        double base = isHand(id) ? 1.8 : 2.3;
        if (sensitivity == 1) return base * 1.42;
        if (sensitivity == 3) return base * 0.72;
        return base;
    }

    private int supportJointId(int id) {
        if (id == PoseLandmark.LEFT_WRIST) return PoseLandmark.LEFT_ELBOW;
        if (id == PoseLandmark.RIGHT_WRIST) return PoseLandmark.RIGHT_ELBOW;
        if (id == PoseLandmark.LEFT_ANKLE) return PoseLandmark.LEFT_KNEE;
        return PoseLandmark.RIGHT_KNEE;
    }

    private boolean isHand(int id) { return id == PoseLandmark.LEFT_WRIST || id == PoseLandmark.RIGHT_WRIST; }
    private boolean isLeft(int id) { return id == PoseLandmark.LEFT_WRIST || id == PoseLandmark.LEFT_ANKLE; }

    private String limbName(int id) {
        if (id == PoseLandmark.LEFT_WRIST) return "Left hand";
        if (id == PoseLandmark.RIGHT_WRIST) return "Right hand";
        if (id == PoseLandmark.LEFT_ANKLE) return "Left foot";
        return "Right foot";
    }

    private String gradeFor(double c) {
        if (c >= 0.85) return "A";
        if (c >= 0.70) return "B";
        if (c >= 0.55) return "C";
        return "D";
    }

    private static class StrikeResult {
        int weaponId;
        boolean isLeft;
        String technique;
        double peakMph, avgMph, recoilMph, peakAccelFt;
        double timeToPeakMs, durationMs, pathFt, extensionIn;
        double chainScore;
        String leakLabel;
        double effectiveMassLb, momentumImperial, energyFtLb, forceLowLbf, forceHighLbf;
        double confidence;
        boolean newPb;
        long elapsedMs;
    }

    private static class SessionSummary {
        int count, newPbCount;
        double fastestMph, averageMph, velocityRetentionPct, avgChainScore, avgConfidence, asymmetryPct;
        double highestEnergyFtLb, highestMomentum, avgTransitionMs, firstHalfMph, secondHalfMph;
        String insight;
    }

    private boolean good(PoseLandmark lm) { return lm != null && lm.getInFrameLikelihood() >= 0.45f; }

    private PointF centerOf(PoseLandmark a, PoseLandmark b) {
        if (good(a) && good(b)) return new PointF((a.getPosition().x + b.getPosition().x) / 2f, (a.getPosition().y + b.getPosition().y) / 2f);
        if (good(a)) return a.getPosition();
        if (good(b)) return b.getPosition();
        return null;
    }

    private PointF centerOfPoints(PointF a, PointF b) {
        if (a != null && b != null) return new PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f);
        return a != null ? a : b;
    }

    private PointF copy(PointF p) { return p == null ? null : new PointF(p.x, p.y); }

    private double dist(PointF a, PointF b) {
        if (a == null || b == null) return 0;
        double dx = a.x - b.x, dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int index = (int)Math.round((sorted.size() - 1) * p);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    private String fmt(double x) {
        if (Math.abs(x - Math.round(x)) < 0.01) return String.valueOf((int)Math.round(x));
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
        tv.setPadding(0, 18, 0, 7);
        return tv;
    }

    private TextView cardText(String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18, 15, 18, 15);
        card.setBackground(roundBg(Color.argb(210, 17, 17, 17)));
        TextView h = text(title, 15, Color.WHITE, Typeface.BOLD);
        TextView b = text(body, 14, Color.rgb(230, 230, 230), Typeface.NORMAL);
        card.addView(h);
        card.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 10, 0, 8);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 82, 1);
        lp.setMargins(4, 2, 4, 2);
        e.setLayoutParams(lp);
        e.setBackground(roundBg(Color.argb(195, 30, 30, 30)));
        e.setPadding(12, 0, 12, 0);
        return e;
    }

    private TextView text(String s, int size, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, style);
        tv.setPadding(0, 4, 0, 4);
        return tv;
    }

    private Button largeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(roundBg(Color.rgb(42, 115, 242)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 106);
        lp.setMargins(0, 7, 0, 7);
        b.setLayoutParams(lp);
        return b;
    }

    private Button smallButton(String label) {
        Button b = largeButton(label);
        b.setTextSize(11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 86, 1);
        lp.setMargins(4, 3, 4, 3);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable roundBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(22f);
        g.setStroke(1, Color.rgb(66, 66, 66));
        return g;
    }
}
