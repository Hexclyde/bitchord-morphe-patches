package app.bitchord.soulseek;

/** Frozen row geometry for a drag: every frame derives from the original finger displacement. */
final class FormatDragOrder {
    private final int[] tops, heights;
    private final int start;
    private float offset;
    private int target;

    FormatDragOrder(int[] tops, int[] heights, int start) {
        if (tops == null
                || heights == null
                || tops.length != heights.length
                || start < 0
                || start >= tops.length) throw new IllegalArgumentException("Invalid rows");
        this.tops = tops.clone();
        this.heights = heights.clone();
        this.start = this.target = start;
        for (int i = 0; i < tops.length; i++) {
            if (heights[i] <= 0 || (i > 0 && tops[i] < tops[i - 1] + heights[i - 1])) {
                throw new IllegalArgumentException("Invalid row geometry");
            }
        }
    }

    void update(float delta) {
        if (Float.isNaN(delta) || Float.isInfinite(delta)) return;
        offset = Math.max(slot(0), Math.min(slot(tops.length - 1), delta));
        target = start;
        if (offset >= 0) {
            while (target + 1 < tops.length && offset > (slot(target) + slot(target + 1)) / 2f)
                target++;
        } else {
            while (target > 0 && offset < (slot(target) + slot(target - 1)) / 2f) target--;
        }
    }

    private float slot(int at) {
        if (at < start) return tops[at] - tops[start];
        if (at > start) return tops[at] + heights[at] - heights[start] - tops[start];
        return 0;
    }

    float translation(int index) {
        if (index == start) return offset;
        if (target > start && index > start && index <= target) return -heights[start];
        if (target < start && index >= target && index < start) return heights[start];
        return 0;
    }

    int start() {
        return start;
    }

    int target() {
        return target;
    }
}
