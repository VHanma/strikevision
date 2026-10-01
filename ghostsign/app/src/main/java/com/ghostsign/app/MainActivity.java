package com.ghostsign.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.text.DateFormat;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 17, 20);
    private static final int PANEL = Color.rgb(29, 31, 37);
    private static final int TEXT = Color.rgb(244, 244, 248);
    private static final int MUTED = Color.rgb(177, 179, 190);
    private static final int ACCENT = Color.rgb(122, 105, 255);

    private EditText editor;
    private EditText customSignatureEditor;
    private TextView serviceState;
    private TextView status;
    private TextView fingerprint;
    private TextView opacityLabel;
    private TextView signaturePreview;
    private CheckBox messageOnly;
    private SeekBar opacitySeek;

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
        TextView subtitle = text(
                "Hybrid mode: share ordinary text with hidden proof plus a PNG that preserves the exact signature opacity.",
                16, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        page.addView(subtitle);

        serviceState = text("Checking automatic mode…", 15, TEXT, true);
        page.addView(serviceState);

        Button accessibility = button("ENABLE AUTOMATIC WATERMARKING");
        accessibility.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
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

        TextView identityLabel = text("YOUR CRYPTOGRAPHIC IDENTITY", 12, MUTED, true);
        identityLabel.setPadding(0, dp(10), 0, dp(4));
        page.addView(identityLabel);
        fingerprint = text("Generating secure key…", 16, TEXT, true);
        fingerprint.setTextIsSelectable(true);
        page.addView(fingerprint);

        TextView identityNote = text(
                "Keep this fingerprint. The text watermark proves authorship. The image preserves the exact visible opacity of your chosen signature.",
                13, MUTED, false);
        identityNote.setPadding(0, dp(6), 0, dp(22));
        page.addView(identityNote);

        TextView customLabel = text("YOUR CUSTOM SIGNATURE", 12, MUTED, true);
        page.addView(customLabel);

        customSignatureEditor = new EditText(this);
        customSignatureEditor.setTextColor(TEXT);
        customSignatureEditor.setHintTextColor(MUTED);
        customSignatureEditor.setHint("Type anything: Vaan Hanma, a phrase, code, symbol, or full sentence");
        customSignatureEditor.setTextSize(17);
        customSignatureEditor.setMinLines(2);
        customSignatureEditor.setMaxLines(5);
        customSignatureEditor.setGravity(Gravity.TOP | Gravity.START);
        customSignatureEditor.setPadding(dp(14), dp(12), dp(14), dp(12));
        customSignatureEditor.setBackgroundColor(PANEL);
        customSignatureEditor.setText(getSharedPreferences("ghostsign", MODE_PRIVATE)
                .getString("custom_signature", "Vaan Hanma"));
        LinearLayout.LayoutParams signatureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        signatureParams.setMargins(0, dp(8), 0, dp(8));
        page.addView(customSignatureEditor, signatureParams);

        opacityLabel = text("Image signature opacity: 1%", 14, TEXT, true);
        opacityLabel.setPadding(0, dp(5), 0, 0);
        page.addView(opacityLabel);

        opacitySeek = new SeekBar(this);
        opacitySeek.setMax(100);
        int savedOpacity = getSharedPreferences("ghostsign", MODE_PRIVATE)
                .getInt("signature_opacity", 1);
        if (savedOpacity < 1) {
            savedOpacity = 1;
        }
        opacitySeek.setProgress(savedOpacity);
        opacitySeek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        opacitySeek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        page.addView(opacitySeek);

        TextView previewLabel = text("IMAGE SIGNATURE PREVIEW", 12, MUTED, true);
        previewLabel.setPadding(0, dp(6), 0, dp(4));
        page.addView(previewLabel);

        signaturePreview = text("", 18, TEXT, false);
        signaturePreview.setGravity(Gravity.END);
        signaturePreview.setPadding(dp(14), dp(12), dp(14), dp(12));
        signaturePreview.setBackgroundColor(PANEL);
        page.addView(signaturePreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)));

        TextView opacityNote = text(
                "The slider controls the PNG signature only. The plain text share stays ordinary text with an invisible cryptographic proof because raw text cannot carry reliable cross-phone opacity.",
                13, MUTED, false);
        opacityNote.setPadding(0, dp(8), 0, dp(20));
        page.addView(opacityNote);

        customSignatureEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                getSharedPreferences("ghostsign", MODE_PRIVATE)
                        .edit().putString("custom_signature", s.toString()).apply();
                updateSignaturePreview();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = Math.max(1, progress);
                getSharedPreferences("ghostsign", MODE_PRIVATE)
                        .edit().putInt("signature_opacity", actual).apply();
                updateSignaturePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (seekBar.getProgress() < 1) {
                    seekBar.setProgress(1);
                }
            }
        });
        updateSignaturePreview();

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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        editorParams.setMargins(0, dp(8), 0, dp(10));
        page.addView(editor, editorParams);

        Button sign = button("ADD INVISIBLE TEXT WATERMARK");
        sign.setOnClickListener(view -> signEditor());
        page.addView(sign);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("COPY TEXT");
        Button paste = button("PASTE");
        Button verify = button("VERIFY");
        row.addView(copy, weighted());
        row.addView(paste, weighted());
        row.addView(verify, weighted());
        page.addView(row);

        copy.setOnClickListener(view -> copySignedText());
        paste.setOnClickListener(view -> pasteFromClipboard());
        verify.setOnClickListener(view -> verifyEditor());

        Button share = button("SHARE HYBRID TEXT + IMAGE");
        share.setOnClickListener(view -> shareHybridContent());
        page.addView(share);

        Button strip = button("REMOVE HIDDEN WATERMARK FROM BOX");
        strip.setOnClickListener(view -> {
            String clean = GhostWatermark.stripWatermarks(editor.getText().toString());
            editor.setText(clean);
            editor.setSelection(editor.length());
            setStatus("Hidden watermark removed. Visible text was left intact.", true);
        });
        page.addView(strip);

        status = text(
                "Hybrid share sends normal text with hidden proof and a PNG with exact opacity.",
                14, MUTED, false);
        status.setPadding(0, dp(16), 0, dp(10));
        status.setTextIsSelectable(true);
        page.addView(status);

        TextView limits = text(
                "Important: the image preserves the exact visible signature opacity on any phone that displays images. The plain text portion carries the hidden proof, but some apps may ignore the extra text when sharing an image attachment.",
                13, MUTED, false);
        page.addView(limits);

        return scroll;
    }

    private void updateSignaturePreview() {
        if (signaturePreview == null || opacitySeek == null || customSignatureEditor == null) {
            return;
        }
        int opacity = getOpacity();
        String custom = customSignatureEditor.getText().toString();
        opacityLabel.setText("Image signature opacity: " + opacity + "%");
        signaturePreview.setText(custom.isEmpty() ? "(empty signature)" : custom);
        signaturePreview.setAlpha(Math.max(0.01f, opacity / 100f));
        int bytes = GhostWatermark.customSignatureByteCount(custom);
        if (bytes > GhostWatermark.MAX_CUSTOM_SIGNATURE_BYTES) {
            opacityLabel.setText(opacityLabel.getText() + " · signature too long");
            opacityLabel.setTextColor(Color.rgb(255, 145, 145));
        } else {
            opacityLabel.setTextColor(TEXT);
        }
    }

    private String getCustomSignature() {
        return customSignatureEditor.getText().toString();
    }

    private int getOpacity() {
        return Math.max(1, opacitySeek.getProgress());
    }

    private String removeCurrentDisplayedSignature(String text, String custom) {
        if (text == null) {
            return "";
        }
        if (custom == null || custom.isEmpty()) {
            return text;
        }
        String suffix = "\n" + custom;
        if (text.endsWith(suffix)) {
            return text.substring(0, text.length() - suffix.length());
        }
        return text;
    }

    private String getBaseMessage() {
        String withoutHidden = GhostWatermark.stripWatermarks(editor.getText().toString());
        return removeCurrentDisplayedSignature(withoutHidden, getCustomSignature());
    }

    private String createPlainSignedText() throws Exception {
        String custom = getCustomSignature();
        int bytes = GhostWatermark.customSignatureByteCount(custom);
        if (bytes > GhostWatermark.MAX_CUSTOM_SIGNATURE_BYTES) {
            throw new IllegalArgumentException(
                    "Custom signature is " + bytes + " bytes; maximum is "
                            + GhostWatermark.MAX_CUSTOM_SIGNATURE_BYTES);
        }
        String baseMessage = getBaseMessage();
        if (baseMessage.isEmpty()) {
            throw new IllegalArgumentException("Type a message first");
        }
        return GhostWatermark.sign(baseMessage, custom);
    }

    private void signEditor() {
        try {
            String signed = createPlainSignedText();
            String visible = GhostWatermark.stripWatermarks(signed);
            editor.setText(signed);
            editor.setSelection(editor.length());
            int hidden = signed.length() - visible.length();
            setStatus(
                    "Invisible text watermark added.\n"
                            + hidden + " invisible characters carry the proof.\n"
                            + "Use SHARE HYBRID TEXT + IMAGE to send the text plus a PNG with exact opacity.",
                    true);
        } catch (Exception error) {
            setStatus("Signing failed: " + error.getMessage(), false);
        }
    }

    private void copySignedText() {
        try {
            String plainSigned = createPlainSignedText();
            editor.setText(plainSigned);
            editor.setSelection(editor.length());
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("GhostSign text", plainSigned));
            setStatus("Copied the plain text with hidden proof to the clipboard.", true);
        } catch (Exception error) {
            setStatus("Copy failed: " + error.getMessage(), false);
        }
    }

    private void shareHybridContent() {
        try {
            String plainSigned = createPlainSignedText();
            String baseMessage = GhostWatermark.stripWatermarks(plainSigned);
            Uri imageUri = buildShareImageUri(baseMessage, getCustomSignature(), getOpacity());
            editor.setText(plainSigned);
            editor.setSelection(editor.length());

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, imageUri);
            intent.putExtra(Intent.EXTRA_TEXT, plainSigned);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newUri(getContentResolver(), "GhostSign image", imageUri));
            startActivity(Intent.createChooser(intent, "Share GhostSign hybrid message"));

            setStatus(
                    "Shared hybrid content: normal text with hidden proof plus a PNG carrying the exact "
                            + getOpacity() + "% signature opacity.",
                    true);
        } catch (Exception error) {
            setStatus("Share failed: " + error.getMessage(), false);
        }
    }

    private Uri buildShareImageUri(String message, String signature, int opacity) throws Exception {
        Bitmap bitmap = renderHybridBitmap(message, signature, opacity);
        File sharedDir = new File(getCacheDir(), "shared");
        if (!sharedDir.exists() && !sharedDir.mkdirs()) {
            throw new IllegalStateException("Could not create shared cache directory");
        }
        File output = new File(sharedDir, "ghostsign_hybrid.png");
        FileOutputStream stream = new FileOutputStream(output);
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.flush();
        } finally {
            stream.close();
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", output);
    }

    private Bitmap renderHybridBitmap(String message, String signature, int opacity) {
        int width = 1440;
        int padding = 96;
        int innerWidth = width - (padding * 2);
        int gap = 48;

        TextPaint messagePaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        messagePaint.setColor(Color.rgb(22, 22, 22));
        messagePaint.setTextSize(50f);
        messagePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        TextPaint signaturePaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        signaturePaint.setColor(Color.rgb(22, 22, 22));
        signaturePaint.setTextSize(42f);
        signaturePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        signaturePaint.setAlpha(Math.max(1, Math.round(255f * (opacity / 100f))));

        String safeMessage = message == null ? "" : message;
        String safeSignature = signature == null ? "" : signature;

        StaticLayout messageLayout = StaticLayout.Builder
                .obtain(safeMessage, 0, safeMessage.length(), messagePaint, innerWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build();

        boolean hasSignature = !safeSignature.isEmpty();
        StaticLayout signatureLayout = hasSignature
                ? StaticLayout.Builder
                .obtain(safeSignature, 0, safeSignature.length(), signaturePaint, innerWidth)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setLineSpacing(0f, 1.1f)
                .setIncludePad(false)
                .build()
                : null;

        int height = padding + messageLayout.getHeight() + padding;
        if (signatureLayout != null) {
            height += gap + signatureLayout.getHeight();
        }

        Bitmap bitmap = Bitmap.createBitmap(width, Math.max(height, 500), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        canvas.save();
        canvas.translate(padding, padding);
        messageLayout.draw(canvas);
        canvas.restore();

        if (signatureLayout != null) {
            canvas.save();
            canvas.translate(padding, padding + messageLayout.getHeight() + gap);
            signatureLayout.draw(canvas);
            canvas.restore();
        }

        return bitmap;
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()
                && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence value = clipboard.getPrimaryClip()
                    .getItemAt(0).coerceToText(this);
            editor.setText(value);
            editor.setSelection(editor.length());
            setStatus("Pasted. Tap Verify to inspect the hidden proof.", true);
        }
    }

    private void verifyEditor() {
        GhostWatermark.Verification result =
                GhostWatermark.verify(editor.getText().toString());
        if (result.valid) {
            String date = DateFormat.getDateTimeInstance().format(result.signedAt());
            String chosen = result.customSignature.isEmpty()
                    ? "(empty)"
                    : result.customSignature;
            try {
                String currentIdentity = GhostWatermark.currentFingerprint();
                boolean mine = currentIdentity.equals(result.fingerprint);
                if (mine) {
                    setStatus(
                            "AUTHENTIC — YOUR SIGNING IDENTITY\n"
                                    + "Chosen signature: " + chosen + "\n"
                                    + "Identity: " + result.fingerprint + "\n"
                                    + "Signed: " + date + "\n"
                                    + "The visible text still matches your cryptographic proof.",
                            true);
                } else {
                    setStatus(
                            "VALID SIGNATURE — DIFFERENT IDENTITY\n"
                                    + "Chosen signature: " + chosen + "\n"
                                    + "Identity: " + result.fingerprint + "\n"
                                    + "Signed: " + date + "\n"
                                    + "The text is intact, but this phone did not sign it.",
                            false);
                }
            } catch (Exception error) {
                setStatus(
                        "VALID SIGNATURE\n"
                                + "Chosen signature: " + chosen + "\n"
                                + "Identity: " + result.fingerprint + "\n"
                                + "Signed: " + date,
                        true);
            }
        } else {
            setStatus(
                    "NOT VERIFIED\n" + result.message
                            + (result.customSignature.isEmpty()
                            ? ""
                            : "\nEmbedded signature: " + result.customSignature)
                            + (result.fingerprint.isEmpty()
                            ? ""
                            : "\nClaimed identity: " + result.fingerprint),
                    false);
        }
    }

    private void loadIdentity() {
        try {
            fingerprint.setText(GhostWatermark.currentFingerprint());
        } catch (Exception error) {
            fingerprint.setText(
                    "Could not create signing identity: " + error.getMessage());
        }
    }

    private void updateServiceState() {
        boolean enabled = isAccessibilityServiceEnabled();
        serviceState.setText(enabled
                ? "● Automatic text watermarking is ON"
                : "○ Automatic text watermarking is OFF");
        serviceState.setTextColor(enabled
                ? Color.rgb(118, 232, 166)
                : Color.rgb(255, 184, 92));
    }

    private boolean isAccessibilityServiceEnabled() {
        int enabled = Settings.Secure.getInt(
                getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0);
        if (enabled != 1) {
            return false;
        }
        String expected = new ComponentName(
                this,
                GhostSignAccessibilityService.class).flattenToString();
        String services = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter =
                new TextUtils.SimpleStringSplitter(':');
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
        status.setTextColor(good
                ? Color.rgb(150, 235, 183)
                : Color.rgb(255, 145, 145));
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
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52));
        params.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(50), 1f);
        params.setMargins(dp(2), dp(4), dp(2), dp(4));
        return params;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
