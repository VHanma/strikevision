package com.ghostsign.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 17, 20);
    private static final int PANEL = Color.rgb(29, 31, 37);
    private static final int TEXT = Color.rgb(244, 244, 248);
    private static final int MUTED = Color.rgb(177, 179, 190);
    private static final int ACCENT = Color.rgb(122, 105, 255);

    private EditText editor;
    private TextView serviceState;
    private TextView status;
    private TextView fingerprint;
    private CheckBox messageOnly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        loadIdentity();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceState();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("GhostSign", 30, TEXT, true);
        page.addView(title);
        TextView subtitle = text("Invisible cryptographic authorship marks for ordinary text.", 16, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        page.addView(subtitle);

        serviceState = text("Checking automatic mode…", 15, TEXT, true);
        page.addView(serviceState);

        Button accessibility = button("ENABLE AUTOMATIC WATERMARKING");
        accessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        page.addView(accessibility);

        messageOnly = new CheckBox(this);
        messageOnly.setText("Only sign fields near a Send, Post, Reply, or Comment button");
        messageOnly.setTextColor(TEXT);
        messageOnly.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        messageOnly.setChecked(getSharedPreferences("ghostsign", MODE_PRIVATE)
                .getBoolean("message_only", true));
        messageOnly.setPadding(0, dp(8), 0, dp(16));
        messageOnly.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences("ghostsign", MODE_PRIVATE)
                        .edit().putBoolean("message_only", isChecked).apply());
        page.addView(messageOnly);

        TextView identityLabel = text("YOUR SIGNING IDENTITY", 12, MUTED, true);
        identityLabel.setPadding(0, dp(10), 0, dp(4));
        page.addView(identityLabel);
        fingerprint = text("Generating secure key…", 16, TEXT, true);
        fingerprint.setTextIsSelectable(true);
        page.addView(fingerprint);

        TextView identityNote = text(
                "Keep this fingerprint. Messages signed by your phone verify against this identity. The public key travels invisibly with each message; the private key stays in Android Keystore.",
                13, MUTED, false);
        identityNote.setPadding(0, dp(6), 0, dp(22));
        page.addView(identityNote);

        TextView testLabel = text("SIGN OR VERIFY TEXT", 12, MUTED, true);
        page.addView(testLabel);

        editor = new EditText(this);
        editor.setTextColor(TEXT);
        editor.setHintTextColor(MUTED);
        editor.setHint("Type or paste a message here…");
        editor.setTextSize(17);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(6);
        editor.setPadding(dp(14), dp(14), dp(14), dp(14));
        editor.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editorParams.setMargins(0, dp(8), 0, dp(10));
        page.addView(editor, editorParams);

        Button sign = button("ADD INVISIBLE WATERMARK");
        sign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                signEditor();
            }
        });
        page.addView(sign);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("COPY");
        Button paste = button("PASTE");
        Button verify = button("VERIFY");
        row.addView(copy, weighted());
        row.addView(paste, weighted());
        row.addView(verify, weighted());
        page.addView(row);

        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("GhostSign text", editor.getText()));
                setStatus("Copied as ordinary text. Invisible watermark preserved in the clipboard.", true);
            }
        });
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                    editor.setText(value);
                    editor.setSelection(editor.length());
                    setStatus("Pasted. Tap Verify to inspect the hidden signature.", true);
                }
            }
        });
        verify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                verifyEditor();
            }
        });

        Button share = button("SHARE SIGNED TEXT");
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, editor.getText().toString());
                startActivity(Intent.createChooser(intent, "Share GhostSign text"));
            }
        });
        page.addView(share);

        Button strip = button("REMOVE WATERMARK FROM BOX");
        strip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String clean = GhostWatermark.stripWatermarks(editor.getText().toString());
                editor.setText(clean);
                editor.setSelection(editor.length());
                setStatus("Watermark removed from the text box.", true);
            }
        });
        page.addView(strip);

        status = text("The watermark is invisible Unicode text, not an image or attachment.", 14, MUTED, false);
        status.setPadding(0, dp(16), 0, dp(10));
        status.setTextIsSelectable(true);
        page.addView(status);

        TextView limits = text(
                "Important: some apps erase zero-width Unicode when sending, copying, translating, or sanitizing text. GhostSign can detect that the mark is missing, but no invisible plain-text method can survive a platform that deliberately removes every invisible character.",
                13, MUTED, false);
        page.addView(limits);

        return scroll;
    }

    private void signEditor() {
        try {
            String visible = GhostWatermark.stripWatermarks(editor.getText().toString());
            if (visible.isEmpty()) {
                setStatus("Type a message first.", false);
                return;
            }
            String signed = GhostWatermark.sign(visible);
            editor.setText(signed);
            editor.setSelection(editor.length());
            int hidden = signed.length() - visible.length();
            setStatus("Signed. " + hidden + " invisible characters carry the verification payload.", true);
        } catch (Exception error) {
            setStatus("Signing failed: " + error.getMessage(), false);
        }
    }

    private void verifyEditor() {
        GhostWatermark.Verification result = GhostWatermark.verify(editor.getText().toString());
        if (result.valid) {
            String date = DateFormat.getDateTimeInstance().format(result.signedAt());
            setStatus("AUTHENTIC\nIdentity: " + result.fingerprint + "\nSigned: " + date + "\nThe visible text still matches the signature.", true);
        } else {
            setStatus("NOT VERIFIED\n" + result.message
                    + (result.fingerprint.isEmpty() ? "" : "\nClaimed identity: " + result.fingerprint), false);
        }
    }

    private void loadIdentity() {
        try {
            fingerprint.setText(GhostWatermark.currentFingerprint());
        } catch (Exception error) {
            fingerprint.setText("Could not create signing identity: " + error.getMessage());
        }
    }

    private void updateServiceState() {
        boolean enabled = isAccessibilityServiceEnabled();
        serviceState.setText(enabled
                ? "● Automatic watermarking is ON"
                : "○ Automatic watermarking is OFF");
        serviceState.setTextColor(enabled ? Color.rgb(118, 232, 166) : Color.rgb(255, 184, 92));
    }

    private boolean isAccessibilityServiceEnabled() {
        int enabled = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        if (enabled != 1) {
            return false;
        }
        String expected = new ComponentName(this, GhostSignAccessibilityService.class).flattenToString();
        String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(services);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    private void setStatus(String value, boolean good) {
        status.setText(value);
        status.setTextColor(good ? Color.rgb(150, 235, 183) : Color.rgb(255, 145, 145));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1f);
        params.setMargins(dp(2), dp(4), dp(2), dp(4));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
