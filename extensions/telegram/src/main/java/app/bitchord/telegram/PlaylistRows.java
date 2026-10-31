package app.bitchord.telegram;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

/** Compact action rows shared by the native playlist screens. */
final class PlaylistRows {
    private static final String GROUP = "patch-settings-group";

    static LinearLayout group(LinearLayout parent, TelegramStyle style) {
        LinearLayout group = new LinearLayout(style.activity);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setTag(GROUP);
        group.setBackground(style.shape(style.card, 16, false));
        group.setClipToOutline(true);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.bottomMargin = style.dp(4);
        parent.addView(group, space);
        return group;
    }

    static View add(
            LinearLayout parent,
            TelegramStyle style,
            String title,
            String subtitle,
            Runnable action) {
        boolean grouped = GROUP.equals(parent.getTag());
        if (grouped && parent.getChildCount() > 0) {
            View divider = new View(style.activity);
            divider.setBackgroundColor(style.outline);
            LinearLayout.LayoutParams line =
                    new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f)));
            line.leftMargin = style.dp(16);
            line.rightMargin = style.dp(16);
            parent.addView(divider, line);
        }
        LinearLayout row = new LinearLayout(style.activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(subtitle.isEmpty() ? 52 : 64));
        row.setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(12));
        row.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(style.dark ? 0x228e8e93 : 0x22000000),
                        new ColorDrawable(Color.TRANSPARENT),
                        null));
        LinearLayout content = new LinearLayout(style.activity);
        content.setOrientation(1);
        TextView heading = style.text(title, 16, style.foreground, style.medium);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(heading);
        if (!subtitle.isEmpty()) {
            TextView detail = style.text(subtitle, 13, style.secondary, style.regular);
            detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.addView(detail);
        }
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        View arrow =
                new View(style.activity) {
                    final Paint paint = new Paint(3);

                    @Override
                    protected void onDraw(Canvas canvas) {
                        paint.setColor(style.secondary);
                        paint.setStrokeWidth(style.dp(1.7f));
                        paint.setStrokeCap(Paint.Cap.ROUND);
                        float x = getWidth() / 2f, y = getHeight() / 2f;
                        float direction =
                                row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                        canvas.drawLine(
                                x - direction * style.dp(3),
                                y - style.dp(5),
                                x + direction * style.dp(2),
                                y,
                                paint);
                        canvas.drawLine(
                                x + direction * style.dp(2),
                                y,
                                x - direction * style.dp(3),
                                y + style.dp(5),
                                paint);
                    }
                };
        row.addView(arrow, new LinearLayout.LayoutParams(style.dp(24), style.dp(24)));
        row.setContentDescription(title + (subtitle.isEmpty() ? "" : ", " + subtitle));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        row.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                    }
                });
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2);
        placement.bottomMargin = grouped ? 0 : style.dp(4);
        parent.addView(row, placement);
        return row;
    }
}
