package com.strikevision.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class OmegaHubActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(3, 7, 13), Color.rgb(9, 17, 28), Color.rgb(3, 7, 13)});
        scroll.setBackground(backdrop);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 48, 28, 42);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView title = text("STRIKEVISION Ω4", 30, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.09f);
        root.addView(title);
        TextView sub = text("COMBAT BIOMECHANICS OBSERVATORY", 12, lime(), Typeface.BOLD);
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.08f);
        root.addView(sub);

        LinearLayout statusRail = new LinearLayout(this);
        statusRail.setOrientation(LinearLayout.HORIZONTAL);
        statusRail.setGravity(Gravity.CENTER);
        statusRail.setPadding(0, 18, 0, 8);
        statusRail.addView(badge("LOCAL ENGINE", lime()));
        statusRail.addView(badge("FRONT CAMERA", cyan()));
        statusRail.addView(badge("Ω LABS", Color.rgb(180, 126, 255)));
        root.addView(statusRail);

        View rule = new View(this);
        rule.setBackground(buttonGradient());
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(-1, 4);
        ruleLp.setMargins(48, 8, 48, 18);
        root.addView(rule, ruleLp);

        TextView desc = text("Live motion intelligence, high-speed evidence and fighter-specific performance memory in one local command deck.", 13, Color.LTGRAY, Typeface.NORMAL);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 4, 0, 22);
        root.addView(desc);

        root.addView(card("01", "LIVE Ω TELEMETRY", "Velocity, power, combinations, endurance, technique, kinetic-chain timing and confidence in real time.", OmegaActivity.class, cyan()));
        root.addView(card("02", "HIGH-SPEED LAB", "Probe the front camera for its strongest recording mode, then fall back cleanly when hardware limits it.", OmegaHighSpeedActivity.class, lime()));
        root.addView(card("03", "DIGITAL FIGHTER TWIN", "Build stance, side and technique-specific baselines from every accepted session.", OmegaTwinActivity.class, Color.rgb(180, 126, 255)));
        root.addView(card("04", "REPLAY LIBRARY", "Review synchronized video, skeletons, weapon trails, metrics and evidence-linked coaching.", OmegaReplayActivity.class, Color.rgb(255, 156, 60)));
        root.addView(card("05", "Ω RESEARCH MODE", "Inspect raw paths, calibration, confidence, timing chains and alternate impact models.", OmegaResearchActivity.class, Color.rgb(255, 82, 125)));

        TextView foot = text("Measured values stay separate from derived physics and modeled estimates. No remote speed server is required.", 11, Color.GRAY, Typeface.NORMAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, 24, 0, 10);
        root.addView(foot);
    }

    private LinearLayout card(String code, String title, String body, Class<?> target, int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20, 18, 20, 18);
        box.setBackground(round(Color.rgb(13, 21, 31), accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 8, 0, 8);
        box.setLayoutParams(lp);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView index = text(code, 12, accent, Typeface.BOLD);
        index.setGravity(Gravity.CENTER);
        index.setBackground(round(Color.rgb(8, 13, 20), accent));
        heading.addView(index, new LinearLayout.LayoutParams(72, 54));
        TextView t = text(title, 16, Color.WHITE, Typeface.BOLD);
        t.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.setMargins(14, 0, 0, 0);
        heading.addView(t, titleLp);
        TextView b = text(body, 12, Color.LTGRAY, Typeface.NORMAL);
        b.setPadding(0, 10, 0, 14);
        Button open = new Button(this);
        open.setAllCaps(false);
        open.setText("ENTER MODULE  →");
        open.setTextColor(Color.WHITE);
        open.setTextSize(13);
        open.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        open.setBackground(buttonGradient());
        open.setOnClickListener(v -> startActivity(new Intent(this, target)));
        box.addView(heading);
        box.addView(b);
        box.addView(open, new LinearLayout.LayoutParams(-1, 90));
        return box;
    }

    private TextView badge(String label, int accent) {
        TextView badge = text(label, 9, accent, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(Color.rgb(10, 17, 25), accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 54, 1f);
        lp.setMargins(4, 0, 4, 0);
        badge.setLayoutParams(lp);
        return badge;
    }

    private TextView text(String s, int size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private int cyan() { return Color.rgb(72, 194, 255); }
    private int lime() { return Color.rgb(194, 255, 48); }

    private GradientDrawable buttonGradient() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(38, 96, 232), Color.rgb(19, 171, 226)});
        g.setCornerRadius(22f);
        g.setStroke(1, Color.rgb(114, 216, 255));
        return g;
    }

    private GradientDrawable round(int color) {
        return round(color, Color.rgb(48, 56, 68));
    }

    private GradientDrawable round(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(24f);
        g.setStroke(1, stroke);
        return g;
    }
}
