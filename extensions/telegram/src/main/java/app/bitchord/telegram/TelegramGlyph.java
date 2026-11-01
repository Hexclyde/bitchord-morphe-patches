package app.bitchord.telegram;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Theme-aware line icons for Telegram's native task rows. */
final class TelegramGlyph extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    private final int size, color;

    TelegramGlyph(TelegramStyle style, String kind, int color) {
        this.kind = kind;
        this.color = color;
        size = style.dp(22);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    static String kind(String label) {
        String value = label.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("connected")) return "check";
        if (value.contains("api")
                || value.contains("application")
                || value.contains("account")
                || value.contains("session")) return "key";
        if (value.contains("cache") || value.contains("storage")) return "storage";
        if (value.contains("backup") || value.contains("back up") || value.contains("restore"))
            return "backup";
        if (value.contains("index")) return "index";
        if (value.contains("playback") || value.contains("source")) return "play";
        if (value.contains("quality") || value.contains("track") || value.contains("name"))
            return "music";
        if (value.contains("download")
                || value.contains("playlist")
                || value.contains("collection")) return "folder";
        if (value.contains("channel") || value.contains("chat")) return "chat";
        if (value.contains("privacy") || value.contains("sign out")) return "lock";
        if (value.contains("sharing") || value.contains("share")) return "share";
        return "settings";
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setColor(color);
        Path p = new Path();
        switch (kind) {
            case "chevron":
                p.moveTo(9, 5);
                p.lineTo(16, 12);
                p.lineTo(9, 19);
                break;
            case "down":
                p.moveTo(5, 9);
                p.lineTo(12, 16);
                p.lineTo(19, 9);
                break;
            case "check":
                canvas.drawCircle(12, 12, 9, paint);
                p.moveTo(7, 12);
                p.lineTo(10, 15);
                p.lineTo(17, 8);
                break;
            case "key":
                canvas.drawCircle(8, 9, 5, paint);
                p.moveTo(12, 13);
                p.lineTo(20, 21);
                p.lineTo(23, 18);
                p.moveTo(17, 18);
                p.lineTo(19, 16);
                break;
            case "chat":
                p.moveTo(4, 4);
                p.lineTo(20, 4);
                p.lineTo(20, 16);
                p.lineTo(10, 16);
                p.lineTo(4, 21);
                p.close();
                p.moveTo(8, 8);
                p.lineTo(16, 8);
                p.moveTo(8, 12);
                p.lineTo(14, 12);
                break;
            case "folder":
                p.moveTo(3, 6);
                p.lineTo(9, 6);
                p.lineTo(12, 9);
                p.lineTo(21, 9);
                p.lineTo(21, 20);
                p.lineTo(3, 20);
                p.close();
                break;
            case "storage":
            case "index":
                canvas.drawOval(4, 3, 20, 9, paint);
                canvas.drawArc(4, 9, 20, 15, 0, 180, false, paint);
                canvas.drawArc(4, 15, 20, 21, 0, 180, false, paint);
                p.moveTo(4, 6);
                p.lineTo(4, 18);
                p.moveTo(20, 6);
                p.lineTo(20, 18);
                break;
            case "music":
                p.moveTo(8, 18);
                p.lineTo(8, 5);
                p.lineTo(20, 3);
                p.lineTo(20, 16);
                canvas.drawOval(2, 16, 8, 21, paint);
                canvas.drawOval(14, 14, 20, 19, paint);
                break;
            case "play":
                p.moveTo(7, 4);
                p.lineTo(20, 12);
                p.lineTo(7, 20);
                p.close();
                break;
            case "backup":
                canvas.drawArc(3, 3, 21, 21, 30, 285, false, paint);
                p.moveTo(21, 3);
                p.lineTo(21, 9);
                p.lineTo(15, 9);
                p.moveTo(12, 7);
                p.lineTo(12, 12);
                p.lineTo(15, 14);
                break;
            case "lock":
                canvas.drawRoundRect(5, 10, 19, 21, 2, 2, paint);
                canvas.drawArc(7, 2, 17, 16, 180, 180, false, paint);
                p.moveTo(12, 14);
                p.lineTo(12, 17);
                break;
            case "share":
                canvas.drawCircle(5, 12, 3, paint);
                canvas.drawCircle(19, 4, 3, paint);
                canvas.drawCircle(19, 20, 3, paint);
                p.moveTo(8, 10);
                p.lineTo(16, 5);
                p.moveTo(8, 14);
                p.lineTo(16, 19);
                break;
            default:
                for (int y = 5; y <= 19; y += 7) {
                    canvas.drawLine(3, y, 21, y, paint);
                    canvas.drawCircle(y == 12 ? 16 : 8, y, 2, paint);
                }
        }
        canvas.drawPath(p, paint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
