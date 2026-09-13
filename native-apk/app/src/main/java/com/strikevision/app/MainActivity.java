package com.strikevision.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StrikeVision Velocity Core v2.0
 *
 * Velocity is camera-derived from frame timestamps and a body-scale calibration.
 * Torso translation is subtracted so stepping/swaying is not counted as strike speed.
 * HITAI-compatible score is deliberately kept separate from modeled physics values.
 */
public class MainActivity extends ComponentActivity {

    private static final double BODY_WEIGHT_LB = 150.0;
    private static final double SHOULDER_WIDTH_IN = 17.0;
    private static final double SHOULDER_WIDTH_M = SHOULDER_WIDTH_IN * 0.0254;

    // These assumptions affect only MODEL energy/force. They do not alter measured MPH.
    private static final double PUNCH_EFFECTIVE_MASS_FRACTION = 0.085;
    private static final double KICK_EFFECTIVE_MASS_FRACTION = 0.20;
    private static final double PUNCH_CONTACT_SEC = 0.025;
    private static final double KICK_CONTACT_SEC = 0.040;

    private static final double MPS_TO_MPH = 2.2369362921;
    private static final double MPH_TO_KMH = 1.609344;
    private static final double J_TO_FTLB = 0.7375621493;
    private static final double N_TO_LBF = 0.2248089431;

    private FrameLayout root;
    private PreviewView previewView;
    private LinearLayout hud;
    private TextView statusText, poseText, velocityText, countText, recordText;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService cameraExecutor;
    private PoseDetector poseDetector;
    private SharedPreferences prefs;

    private int seconds = 5;
    private int sensitivity = 2;
    private int frames = 0;
    private int poses = 0;
    private boolean active = false;
    private boolean processing = false;

    private long roundEndMs = 0L;
    private long lastFrameNs = 0L;
    private long lastStrikeMs = 0L;

    private PointF lastTorso;
    private double lastTorsoZ = 0.0;
    private double shoulderPx = 0.0;
    private double pixelsPerMeter = 0.0;
    private double liveMph = 0.0;
    private double sessionPeakMph = 0.0;

    private final int[] weapons = {
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE
    };

    private final Map<Integer, Sample3> filtered = new HashMap<>();
    private final Map<Integer, Sample3> previous = new HashMap<>();
    private final Map<Integer, Burst> bursts = new HashMap<>();
    private final ArrayList<StrikeResult> strikes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        prefs = getSharedPreferences("sv_velocity_v2", 0);
        cameraExecutor = Executors.newSingleThreadExecutor();
        poseDetector = PoseDetection.getClient(
                new PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        .build()
        );

        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 9);
        } else {
            showMainScreen();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showMainScreen();
        } else {
            TextView denied = new TextView(this);
            denied.setText("Camera permission is required for StrikeVision velocity tracking.");
            denied.setTextColor(Color.WHITE);
            denied.setTextSize(18);
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
    }

    private void showMainScreen() {
        root = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(24, 42, 24, 28);
        hud.setBackgroundColor(Color.argb(72, 0, 0, 0));
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        center("STRIKEVISION", 29, Color.WHITE);
        center("VELOCITY CORE 2.0", 13, Color.rgb(163, 255, 18));
        center("150 lb • 17\" shoulder calibration • MPH first", 12, Color.LTGRAY);

        statusText = center("Starting front camera...", 16, Color.rgb(163, 255, 18));
        poseText = center("Frames: 0 | Poses: 0 | Cal: waiting", 13, Color.LTGRAY);
        velocityText = center("LIVE 0.0 mph | BEST 0.0 mph", 20, Color.rgb(59, 130, 246));
        countText = center("Strikes: 0", 17, Color.WHITE);
        recordText = center(
                "Record: " + fmt1(prefs.getFloat("record_mph", 0f)) + " mph",
                14,
                Color.LTGRAY
        );

        LinearLayout durationRow = new LinearLayout(this);
        durationRow.setOrientation(LinearLayout.HORIZONTAL);
        hud.addView(durationRow);
        Button five = smallButton("5s");
        Button ten = smallButton("10s");
        Button fifteen = smallButton("15s");
        five.setOnClickListener(v -> selectSeconds(5));
        ten.setOnClickListener(v -> selectSeconds(10));
        fifteen.setOnClickListener(v -> selectSeconds(15));
        durationRow.addView(five);
        durationRow.addView(ten);
        durationRow.addView(fifteen);

        LinearLayout sensitivityRow = new LinearLayout(this);
        sensitivityRow.setOrientation(LinearLayout.HORIZONTAL);
        hud.addView(sensitivityRow);
        Button strict = smallButton("Strict");
        Button balanced = smallButton("Balanced");
        Button sensitive = smallButton("Sensitive");
        strict.setOnClickListener(v -> selectSensitivity(1, "Strict"));
        balanced.setOnClickListener(v -> selectSensitivity(2, "Balanced"));
        sensitive.setOnClickListener(v -> selectSensitivity(3, "Sensitive"));
        sensitivityRow.addView(strict);
        sensitivityRow.addView(balanced);
        sensitivityRow.addView(sensitive);

        hud.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        Button start = bigButton("Start 5-count");
        start.setOnClickListener(v -> beginRound());
        hud.addView(start);

        Button clear = bigButton("Clear records");
        clear.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            showMainScreen();
        });
        hud.addView(clear);

        startUiLoop();
        startCamera();
    }

    private void selectSeconds(int value) {
        seconds = value;
        statusText.setText(value + " sec selected");
    }

    private void selectSensitivity(int value, String label) {
        sensitivity = value;
        statusText.setText(label + " detection");
    }

    private void startCamera() {
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
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis
                );
                statusText.setText("Pose live. Side view works best. Keep shoulders + striking hand visible.");
            } catch (Throwable error) {
                statusText.setText("CameraX failed: " + error.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void analyzeFrame(ImageProxy imageProxy) {
        frames++;
        if (processing) {
            imageProxy.close();
            return;
        }
        Image image = imageProxy.getImage();
        if (image == null) {
            imageProxy.close();
            return;
        }

        processing = true;
        long timestampNs = imageProxy.getImageInfo().getTimestamp();
        InputImage input = InputImage.fromMediaImage(
                image,
                imageProxy.getImageInfo().getRotationDegrees()
        );

        poseDetector.process(input)
                .addOnSuccessListener(pose -> processPose(pose, timestampNs))
                .addOnCompleteListener(task -> {
                    processing = false;
                    imageProxy.close();
                });
    }

    private void processPose(Pose pose, long timestampNs) {
        poses++;
        PointF torso = torsoPoint(pose);
        if (torso == null) return;

        PoseLandmark leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        if (good(leftShoulder) && good(rightShoulder)) {
            shoulderPx = distance(leftShoulder.getPosition(), rightShoulder.getPosition());
            if (shoulderPx >= 55.0) {
                pixelsPerMeter = shoulderPx / SHOULDER_WIDTH_M;
            }
        }

        double torsoZ = torsoDepth(pose);

        if (lastFrameNs > 0 && lastTorso != null && pixelsPerMeter > 0) {
            double dt = (timestampNs - lastFrameNs) / 1_000_000_000.0;
            if (dt >= 1.0 / 120.0 && dt <= 0.12) {
                double torsoDx = torso.x - lastTorso.x;
                double torsoDy = torso.y - lastTorso.y;
                double torsoDz = torsoZ - lastTorsoZ;

                double bestMph = 0.0;

                for (int weapon : weapons) {
                    PoseLandmark landmark = pose.getPoseLandmark(weapon);
                    if (!good(landmark)) continue;

                    Sample3 raw = sample(landmark);
                    Sample3 priorFiltered = filtered.get(weapon);
                    double rawStep = priorFiltered == null ? 0.0 : distance3(raw, priorFiltered);
                    double alpha = rawStep > shoulderPx * 0.06 ? 0.78 : 0.52;
                    Sample3 current = priorFiltered == null ? raw : blend(priorFiltered, raw, alpha);
                    filtered.put(weapon, current);

                    Sample3 old = previous.get(weapon);
                    if (old == null) continue;

                    double dxPx = (current.x - old.x) - torsoDx;
                    double dyPx = (current.y - old.y) - torsoDy;
                    double dzPx = ((current.z - old.z) - torsoDz) * 0.60;
                    double displacementPx = Math.sqrt(dxPx * dxPx + dyPx * dyPx + dzPx * dzPx);
                    double meters = displacementPx / pixelsPerMeter;
                    double mph = (meters / dt) * MPS_TO_MPH;

                    if (Double.isNaN(mph) || Double.isInfinite(mph) || mph < 0 || mph > 80) {
                        continue;
                    }

                    updateBurst(weapon, mph, weaponName(weapon), SystemClock.elapsedRealtime());
                    bestMph = Math.max(bestMph, mph);
                }

                liveMph = liveMph * 0.35 + bestMph * 0.65;
                sessionPeakMph = Math.max(sessionPeakMph, bestMph);
            }
        }

        for (int weapon : weapons) {
            Sample3 value = filtered.get(weapon);
            if (value != null) previous.put(weapon, value.copy());
        }

        lastFrameNs = timestampNs;
        lastTorso = new PointF(torso.x, torso.y);
        lastTorsoZ = torsoZ;
    }

    private void updateBurst(int weapon, double mph, String name, long nowMs) {
        if (!active) return;
        if (nowMs >= roundEndMs) {
            finishRound();
            return;
        }

        double startThreshold = sensitivity == 1 ? 8.5 : (sensitivity == 2 ? 6.0 : 4.5);
        Burst burst = bursts.get(weapon);

        if (burst == null) {
            if (mph >= startThreshold) {
                bursts.put(weapon, new Burst(name, mph, nowMs));
            }
            return;
        }

        if (mph > burst.peakMph) {
            burst.peakMph = mph;
        }

        boolean decelerated = mph < Math.max(startThreshold * 0.75, burst.peakMph * 0.48);
        boolean timedOut = nowMs - burst.startedMs > 420;
        if (decelerated || timedOut) {
            commitBurst(weapon, burst, nowMs);
            bursts.remove(weapon);
        }
    }

    private void commitBurst(int weapon, Burst burst, long nowMs) {
        if (nowMs - lastStrikeMs < 220) return;
        if (burst.peakMph < 4.0) return;

        boolean kick = weapon == PoseLandmark.LEFT_ANKLE || weapon == PoseLandmark.RIGHT_ANKLE;
        StrikeResult result = physicsFor(burst.name, burst.peakMph, kick);
        strikes.add(result);
        lastStrikeMs = nowMs;

        float recordMph = prefs.getFloat("record_mph", 0f);
        if (result.mph > recordMph) {
            prefs.edit().putFloat("record_mph", (float) result.mph).apply();
        }
        float recordForce = prefs.getFloat("record_force", 0f);
        if (result.forceLbf > recordForce) {
            prefs.edit().putFloat("record_force", (float) result.forceLbf).apply();
        }
    }

    private StrikeResult physicsFor(String name, double mph, boolean kick) {
        double bodyKg = BODY_WEIGHT_LB * 0.45359237;
        double effectiveMassKg = bodyKg * (kick ? KICK_EFFECTIVE_MASS_FRACTION : PUNCH_EFFECTIVE_MASS_FRACTION);
        double mps = mph / MPS_TO_MPH;
        double joules = 0.5 * effectiveMassKg * mps * mps;
        double energyFtLb = joules * J_TO_FTLB;
        double contactSec = kick ? KICK_CONTACT_SEC : PUNCH_CONTACT_SEC;
        double forceNewtons = effectiveMassKg * mps / contactSec;
        double forceLbf = forceNewtons * N_TO_LBF;

        // HITAI-style compatibility metric discovered from HITAI's own bundle:
        // velocity in km/h multiplied by body weight in pounds.
        double hitaiStyle = (mph * MPH_TO_KMH) * BODY_WEIGHT_LB;

        return new StrikeResult(name, mph, hitaiStyle, energyFtLb, forceLbf);
    }

    private void beginRound() {
        strikes.clear();
        filtered.clear();
        previous.clear();
        bursts.clear();
        lastFrameNs = 0L;
        lastTorso = null;
        lastTorsoZ = 0.0;
        liveMph = 0.0;
        sessionPeakMph = 0.0;
        lastStrikeMs = 0L;
        active = false;

        statusText.setText("5");
        for (int i = 4; i > 0; i--) {
            final int count = i;
            ui.postDelayed(() -> statusText.setText(String.valueOf(count)), (5L - i) * 1000L);
        }

        ui.postDelayed(() -> {
            roundEndMs = SystemClock.elapsedRealtime() + seconds * 1000L;
            active = true;
            statusText.setText("GO • VELOCITY FIRST");
        }, 5000L);

        ui.postDelayed(this::finishRound, 5000L + seconds * 1000L + 500L);
    }

    private void finishRound() {
        if (!active) return;
        active = false;

        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<Integer, Burst> entry : new ArrayList<>(bursts.entrySet())) {
            commitBurst(entry.getKey(), entry.getValue(), now);
        }
        bursts.clear();

        double fastest = 0.0;
        double sum = 0.0;
        double bestForce = 0.0;
        double bestEnergy = 0.0;
        double bestHitai = 0.0;
        for (StrikeResult strike : strikes) {
            fastest = Math.max(fastest, strike.mph);
            sum += strike.mph;
            bestForce = Math.max(bestForce, strike.forceLbf);
            bestEnergy = Math.max(bestEnergy, strike.energyFtLb);
            bestHitai = Math.max(bestHitai, strike.hitaiStyleScore);
        }
        double average = strikes.isEmpty() ? 0.0 : sum / strikes.size();

        hud.removeAllViews();
        center("VELOCITY ROUND COMPLETE", 25, Color.WHITE);
        center(strikes.size() + " clean strikes", 28, Color.rgb(163, 255, 18));
        center("Fastest  " + fmt1(fastest) + " mph", 23, Color.rgb(59, 130, 246));
        center("Average  " + fmt1(average) + " mph", 18, Color.LTGRAY);
        center("HITAI-style score  " + Math.round(bestHitai), 16, Color.LTGRAY);
        center("Model energy  " + Math.round(bestEnergy) + " ft-lb", 16, Color.LTGRAY);
        center("Model force  " + Math.round(bestForce) + " lbf", 16, Color.LTGRAY);
        center("Record  " + fmt1(prefs.getFloat("record_mph", 0f)) + " mph", 16, Color.rgb(163, 255, 18));

        StringBuilder log = new StringBuilder();
        for (int i = 0; i < strikes.size(); i++) {
            StrikeResult strike = strikes.get(i);
            log.append('#').append(i + 1).append(' ')
                    .append(strike.name).append(" | ")
                    .append(fmt1(strike.mph)).append(" mph | H ")
                    .append(Math.round(strike.hitaiStyleScore)).append(" | ")
                    .append(Math.round(strike.energyFtLb)).append(" ft-lb | ")
                    .append(Math.round(strike.forceLbf)).append(" lbf\n");
        }
        if (log.length() == 0) {
            log.append("No clean strike captured. Keep both shoulders and the striking wrist visible. Side view is best for speed.");
        }
        hud.addView(card("Per-strike log", log.toString()));

        center("Velocity = frame timestamp + body scale + torso-motion subtraction.", 12, Color.LTGRAY);
        center("Physics fields are modeled; HITAI-style is velocity × body weight, not Joules.", 12, Color.LTGRAY);

        hud.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        Button again = bigButton("Run again");
        again.setOnClickListener(v -> showMainScreen());
        hud.addView(again);
    }

    private boolean good(PoseLandmark landmark) {
        return landmark != null && landmark.getInFrameLikelihood() >= 0.50f;
    }

    private PointF torsoPoint(Pose pose) {
        ArrayList<PointF> points = new ArrayList<>();
        int[] keys = {
                PoseLandmark.LEFT_SHOULDER,
                PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_HIP
        };
        for (int key : keys) {
            PoseLandmark landmark = pose.getPoseLandmark(key);
            if (good(landmark)) points.add(landmark.getPosition());
        }
        if (points.size() < 2) return null;
        float x = 0, y = 0;
        for (PointF point : points) {
            x += point.x;
            y += point.y;
        }
        return new PointF(x / points.size(), y / points.size());
    }

    private double torsoDepth(Pose pose) {
        double sum = 0.0;
        int count = 0;
        int[] keys = {
                PoseLandmark.LEFT_SHOULDER,
                PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_HIP
        };
        for (int key : keys) {
            PoseLandmark landmark = pose.getPoseLandmark(key);
            if (good(landmark)) {
                sum += landmark.getPosition3D().getZ();
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private Sample3 sample(PoseLandmark landmark) {
        return new Sample3(
                landmark.getPosition().x,
                landmark.getPosition().y,
                landmark.getPosition3D().getZ()
        );
    }

    private Sample3 blend(Sample3 old, Sample3 next, double alpha) {
        return new Sample3(
                old.x * (1.0 - alpha) + next.x * alpha,
                old.y * (1.0 - alpha) + next.y * alpha,
                old.z * (1.0 - alpha) + next.z * alpha
        );
    }

    private double distance3(Sample3 a, Sample3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double distance(PointF a, PointF b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private String weaponName(int key) {
        if (key == PoseLandmark.LEFT_WRIST) return "Left punch";
        if (key == PoseLandmark.RIGHT_WRIST) return "Right punch";
        if (key == PoseLandmark.LEFT_ANKLE) return "Left kick";
        if (key == PoseLandmark.RIGHT_ANKLE) return "Right kick";
        return "Strike";
    }

    private void startUiLoop() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (poseText != null) {
                    String cal = pixelsPerMeter > 0 ? Math.round(shoulderPx) + "px shoulders" : "waiting";
                    poseText.setText("Frames: " + frames + " | Poses: " + poses + " | Cal: " + cal);
                }
                if (velocityText != null) {
                    velocityText.setText("LIVE " + fmt1(liveMph) + " mph | BEST " + fmt1(sessionPeakMph) + " mph");
                }
                if (countText != null) countText.setText("Strikes: " + strikes.size());
                ui.postDelayed(this, 200L);
            }
        }, 200L);
    }

    private String fmt1(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private TextView center(String text, int size, int color) {
        TextView view = textView(text, size, color, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        hud.addView(view);
        return view;
    }

    private TextView textView(String text, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setPadding(0, 6, 0, 6);
        return view;
    }

    private Button bigButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(background(Color.rgb(59, 130, 246)));
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, 116));
        return button;
    }

    private Button smallButton(String text) {
        Button button = bigButton(text);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, 94, 1));
        return button;
    }

    private View card(String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(22, 18, 22, 18);
        card.setBackground(background(Color.rgb(23, 23, 23)));
        card.addView(textView(title, 20, Color.WHITE, Typeface.BOLD));
        card.addView(textView(body, 14, Color.LTGRAY, Typeface.NORMAL));
        return card;
    }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(26);
        drawable.setStroke(2, Color.rgb(45, 45, 45));
        return drawable;
    }

    private static final class Sample3 {
        final double x, y, z;
        Sample3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        Sample3 copy() { return new Sample3(x, y, z); }
    }

    private static final class Burst {
        final String name;
        final long startedMs;
        double peakMph;

        Burst(String name, double mph, long nowMs) {
            this.name = name;
            this.startedMs = nowMs;
            this.peakMph = mph;
        }
    }

    private static final class StrikeResult {
        final String name;
        final double mph;
        final double hitaiStyleScore;
        final double energyFtLb;
        final double forceLbf;

        StrikeResult(String name, double mph, double hitaiStyleScore, double energyFtLb, double forceLbf) {
            this.name = name;
            this.mph = mph;
            this.hitaiStyleScore = hitaiStyleScore;
            this.energyFtLb = energyFtLb;
            this.forceLbf = forceLbf;
        }
    }
}
