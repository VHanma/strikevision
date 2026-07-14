package com.ghostsign.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

public class GhostSignAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long ignoreEventsUntil = 0L;

    private final Runnable signFocusedField = new Runnable() {
        @Override
        public void run() {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) {
                    return;
                }
                CharSequence packageName = root.getPackageName();
                if (packageName != null && getPackageName().contentEquals(packageName)) {
                    return;
                }

                boolean messageOnly = getSharedPreferences("ghostsign", MODE_PRIVATE)
                        .getBoolean("message_only", true);
                if (messageOnly && !containsSendControl(root, 0)) {
                    return;
                }

                AccessibilityNodeInfo field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (field == null || !field.isEditable() || field.isPassword()) {
                    return;
                }
                CharSequence current = field.getText();
                if (current == null) {
                    return;
                }
                String raw = current.toString();
                String visible = GhostWatermark.stripWatermarks(raw);
                if (visible.trim().isEmpty() || visible.length() > 12000) {
                    return;
                }

                if (GhostWatermark.hasWatermark(raw) && GhostWatermark.verify(raw).valid) {
                    return;
                }

                String signed = GhostWatermark.sign(visible);
                Bundle textArguments = new Bundle();
                textArguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        signed);
                ignoreEventsUntil = SystemClock.uptimeMillis() + 900L;
                boolean changed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArguments);
                if (changed) {
                    Bundle selection = new Bundle();
                    selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, signed.length());
                    selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, signed.length());
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
                }
            } catch (Exception ignored) {
                // The service deliberately stays silent in apps that block accessibility text replacement.
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || SystemClock.uptimeMillis() < ignoreEventsUntil) {
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handler.removeCallbacks(signFocusedField);
            handler.postDelayed(signFocusedField, 550L);
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(signFocusedField);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(signFocusedField);
    }

    private boolean containsSendControl(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 18) {
            return false;
        }
        if (node.isClickable()) {
            String label = ((safe(node.getText()) + " " + safe(node.getContentDescription()))
                    .trim().toLowerCase(Locale.US));
            if (label.equals("send")
                    || label.equals("post")
                    || label.equals("reply")
                    || label.equals("comment")
                    || label.equals("submit")
                    || label.contains("send message")
                    || label.contains("send now")
                    || label.contains("post comment")) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && containsSendControl(child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
