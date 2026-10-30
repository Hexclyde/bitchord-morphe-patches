package app.bitchord.soulseek;

import static org.junit.Assert.*;

import org.junit.Test;

public class FormatDragOrderTest {
    @Test
    public void oneFrameCanMoveFromTopToBottomWithoutAccumulatingDrift() {
        FormatDragOrder drag =
                new FormatDragOrder(new int[] {0, 70, 140, 210}, new int[] {70, 70, 70, 70}, 0);
        drag.update(10000);
        assertEquals(3, drag.target());
        assertEquals(210, drag.translation(0), 0);
        assertEquals(-70, drag.translation(3), 0);
        drag.update(10000);
        assertEquals(210, drag.translation(0), 0);
        drag.update(0);
        assertEquals(0, drag.target());
        assertEquals(0, drag.translation(3), 0);
    }

    @Test
    public void largeTextRowsOfDifferentHeightsCanReachBothEnds() {
        FormatDragOrder drag =
                new FormatDragOrder(new int[] {0, 72, 192}, new int[] {72, 120, 80}, 1);
        drag.update(10000);
        assertEquals(2, drag.target());
        assertEquals(80, drag.translation(1), 0);
        assertEquals(-120, drag.translation(2), 0);
        drag.update(-10000);
        assertEquals(0, drag.target());
        assertEquals(-72, drag.translation(1), 0);
        assertEquals(120, drag.translation(0), 0);
    }

    @Test
    public void invalidMotionCannotPoisonTheCurrentDrag() {
        FormatDragOrder drag = new FormatDragOrder(new int[] {0, 76}, new int[] {76, 76}, 1);
        drag.update(-76);
        drag.update(Float.NaN);
        drag.update(Float.POSITIVE_INFINITY);
        assertEquals(0, drag.target());
        assertEquals(-76, drag.translation(1), 0);
    }
}
