package app.bitchord.telegram;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** Native counterpart of BitChord's alert scaffold: frosted card, rules and stacked actions. */
final class PlaylistModal {
    final Dialog dialog;
    final TelegramStyle style;
    final LinearLayout body;

    PlaylistModal(Activity activity, String title, String message) {
        style = new TelegramStyle(activity);
        dialog =
                new Dialog(
                        activity,
                        style.dark
                                ? android.R.style.Theme_Material_Dialog_NoActionBar
                                : android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        body = new LinearLayout(activity);
        body.setOrientation(1);
        body.setClipToOutline(true);
        body.setBackground(style.shape(android.graphics.Color.TRANSPARENT, 14, false));
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(1);
        header.setPadding(style.dp(16), style.dp(19), style.dp(16), style.dp(19));
        TextView heading = style.text(title, 17, style.foreground, style.semibold);
        heading.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading);
        if (message != null && !message.isEmpty()) {
            TextView description = style.text(message, 13, style.foreground, style.regular);
            description.setGravity(Gravity.CENTER);
            description.setPadding(0, style.dp(4), 0, 0);
            header.addView(description);
        }
        body.addView(header);
        dialog.setContentView(body);
        dialog.setCanceledOnTouchOutside(true);
    }

    void rule() {
        View rule = new View(style.activity);
        rule.setBackgroundColor(style.dark ? 0x33ffffff : 0x26000000);
        body.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f))));
    }

    TextView action(String label, boolean primary, Runnable callback) {
        rule();
        TextView button =
                style.text(label, 17, style.foreground, primary ? style.semibold : style.regular);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(style.dp(48));
        button.setPadding(style.dp(16), style.dp(10), style.dp(16), style.dp(10));
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(style.dark ? 0x17ffffff : 0x17000000),
                        new ColorDrawable(Color.TRANSPARENT),
                        null));
        button.setOnClickListener(v -> callback.run());
        button.setClickable(true);
        button.setFocusable(true);
        button.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                    }
                });
        body.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    EditText input(String hint) {
        EditText input = new EditText(style.activity);
        input.setSingleLine(true);
        input.setTextColor(style.foreground);
        input.setHintTextColor(style.secondary);
        input.setTypeface(style.regular);
        input.setTextSize(15);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setPadding(style.dp(12), style.dp(10), style.dp(12), style.dp(10));
        input.setMinHeight(style.dp(48));
        input.setBackground(style.shape(style.dark ? 0x55ffffff : 0x11000000, 18, false));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.setMargins(style.dp(16), 0, style.dp(16), style.dp(14));
        body.addView(input, space);
        return input;
    }

    interface Selected {
        void set(int index, boolean checked);
    }

    void choices(String[] labels, boolean[] checked, Selected callback) {
        ScrollView scroll = new ScrollView(style.activity);
        LinearLayout rows = new LinearLayout(style.activity);
        rows.setOrientation(1);
        scroll.addView(rows);
        int max = style.activity.getResources().getDisplayMetrics().heightPixels / 2;
        LinearLayout.LayoutParams size =
                new LinearLayout.LayoutParams(-1, Math.min(max, labels.length * style.dp(58)));
        body.addView(scroll, size);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(style.activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(style.dp(16), style.dp(9), style.dp(16), style.dp(9));
            row.setMinimumHeight(style.dp(48));
            TextView label = style.text(labels[i], 15, style.foreground, style.regular);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            View check =
                    new View(style.activity) {
                        final Paint paint = new Paint(3);

                        @Override
                        protected void onDraw(Canvas canvas) {
                            if (!checked[index]) return;
                            paint.setColor(style.foreground);
                            paint.setStrokeWidth(style.dp(2));
                            paint.setStrokeCap(Paint.Cap.ROUND);
                            canvas.drawLine(
                                    getWidth() * .18f,
                                    getHeight() * .52f,
                                    getWidth() * .42f,
                                    getHeight() * .74f,
                                    paint);
                            canvas.drawLine(
                                    getWidth() * .42f,
                                    getHeight() * .74f,
                                    getWidth() * .84f,
                                    getHeight() * .25f,
                                    paint);
                        }
                    };
            row.addView(check, new LinearLayout.LayoutParams(style.dp(19), style.dp(19)));
            row.setContentDescription(labels[i]);
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(style.dark ? 0x17ffffff : 0x17000000),
                            new ColorDrawable(Color.TRANSPARENT),
                            null));
            row.setAccessibilityDelegate(
                    new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(
                                View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName(android.widget.CheckBox.class.getName());
                            info.setCheckable(true);
                            info.setChecked(checked[index]);
                        }
                    });
            row.setOnClickListener(
                    v -> {
                        checked[index] = !checked[index];
                        callback.set(index, checked[index]);
                        check.invalidate();
                        row.sendAccessibilityEvent(
                                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                    });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    void show() {
        Window window = dialog.getWindow();
        if (window != null) {
            int color = (style.dark ? 0xe61c1c1e : 0xe6f2f2f7);
            window.setBackgroundDrawable(style.shape(color, 14, false));
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            window.setAttributes(attributes);
            window.setDimAmount(.28f);
            if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(style.dp(24));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if (window != null)
            window.setLayout(
                    Math.min(
                            style.dp(270),
                            style.activity.getResources().getDisplayMetrics().widthPixels
                                    - style.dp(40)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
    }

    void dismiss() {
        dialog.dismiss();
    }
}
