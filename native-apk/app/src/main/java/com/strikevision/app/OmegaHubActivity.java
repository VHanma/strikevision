package com.strikevision.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class OmegaHubActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 7, 10));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 42, 28, 42);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView title = text("STRIKEVISION Ω4", 30, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = text("Combat Biomechanics Observatory", 14, Color.rgb(188,255,44), Typeface.BOLD);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
        TextView desc = text("Live telemetry + high-speed capture + optical-flow reconstruction + synchronized evidence replay + Digital Fighter Twin.", 13, Color.LTGRAY, Typeface.NORMAL);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 16, 0, 28);
        root.addView(desc);

        root.addView(card("LIVE Ω TELEMETRY", "Existing real-time velocity, power, combo, endurance, technique, kinetic-chain, fatigue and confidence engine.", OmegaActivity.class));
        root.addView(card("HIGH-SPEED LAB", "Automatically probes the front camera and selects the best available 240/120 fps high-speed mode. Falls back cleanly when hardware limits it.", OmegaHighSpeedActivity.class));
        root.addView(card("DIGITAL FIGHTER TWIN", "Builds technique-specific baselines and best-strike prototypes from every analyzed session.", OmegaTwinActivity.class));
        root.addView(card("REPLAY LIBRARY", "Open saved sessions with synchronized video, skeleton, weapon trails, metrics and tap-to-evidence coaching.", OmegaReplayActivity.class));
        root.addView(card("Ω RESEARCH MODE", "Raw trajectories, calibration, optical-flow confidence, uncertainty, timing chains and alternative impact-model readouts.", OmegaResearchActivity.class));

        TextView foot = text("Measured values stay separate from derived physics and modeled estimates. No remote speed server is required.", 11, Color.GRAY, Typeface.NORMAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, 24, 0, 10);
        root.addView(foot);
    }

    private LinearLayout card(String title, String body, Class<?> target) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20, 18, 20, 18);
        box.setBackground(round(Color.rgb(18,22,29)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 8, 0, 8);
        box.setLayoutParams(lp);
        TextView t = text(title, 16, Color.WHITE, Typeface.BOLD);
        TextView b = text(body, 12, Color.LTGRAY, Typeface.NORMAL);
        b.setPadding(0, 6, 0, 12);
        Button open = new Button(this);
        open.setAllCaps(false);
        open.setText("Open");
        open.setTextColor(Color.WHITE);
        open.setTextSize(13);
        open.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        open.setBackground(round(Color.rgb(45, 118, 242)));
        open.setOnClickListener(v -> startActivity(new Intent(this, target)));
        box.addView(t);
        box.addView(b);
        box.addView(open, new LinearLayout.LayoutParams(-1, 90));
        return box;
    }

    private TextView text(String s, int size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(24f);
        g.setStroke(1, Color.rgb(48,56,68));
        return g;
    }
}
