/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import java.util.Locale;

/**
 * The arithmetic behind {@link ReelsAmbient}, on plain arrays of pixels so that none of it needs Android: where the
 * picture is inside a frame, what colour each cell of a small grid has, how soft the grid is made, and how a shown
 * grid follows a new one.
 */
final class AmbientMath {
    /** A pixel with less alpha than this is the see-through part of a view, not the picture. */
    static final int OPAQUE_ALPHA = 24;
    /** A row or a column of the picture whose brightest channel is below this counts as black. */
    private static final int BLACK_LEVEL = 14;

    private AmbientMath() {}

    /**
     * The smallest rectangle {left, top, right, bottom} (right and bottom are exclusive) that holds every pixel of
     * {@code argb} that is not see-through, or null when there is none. A video whose picture does not fill its view
     * leaves the rest of the view see-through, so this is where the picture is.
     */
    static int[] contentRect(int[] argb, int w, int h) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if ((argb[row + x] >>> 24) < OPAQUE_ALPHA) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return null;
        return new int[] {minX, minY, maxX + 1, maxY + 1};
    }

    /**
     * How many rows at the top and at the bottom of {@code rect} are black, {top, bottom}: a picture that has its own
     * black bars baked in (the bars are then part of the video, not of the view). Only for the report.
     */
    static int[] blackEdgeRows(int[] argb, int w, int[] rect) {
        int top = 0;
        for (int y = rect[1]; y < rect[3] && rowIsBlack(argb, w, y, rect[0], rect[2]); y++) top++;
        int bottom = 0;
        for (int y = rect[3] - 1; y >= rect[1] + top && rowIsBlack(argb, w, y, rect[0], rect[2]); y--) bottom++;
        return new int[] {top, bottom};
    }

    private static boolean rowIsBlack(int[] argb, int w, int y, int from, int to) {
        for (int x = from; x < to; x++) {
            int p = argb[y * w + x];
            if (Math.max((p >> 16) & 0xFF, Math.max((p >> 8) & 0xFF, p & 0xFF)) > BLACK_LEVEL) return false;
        }
        return true;
    }

    /**
     * Averages the colours inside {@code rect} of the {@code w} by {@code h} picture into {@code out}, a grid of
     * {@code gw} by {@code gh} opaque pixels. Only pixels that are not see-through count; a cell with none gets the
     * nearest colour already found in its row, so a hole never shows as black.
     */
    static void grid(int[] argb, int w, int[] rect, int gw, int gh, int[] out) {
        int cw = rect[2] - rect[0];
        int ch = rect[3] - rect[1];
        boolean[] filled = new boolean[gw * gh];
        for (int gy = 0; gy < gh; gy++) {
            int y0 = rect[1] + gy * ch / gh;
            int y1 = Math.max(y0 + 1, rect[1] + (gy + 1) * ch / gh);
            for (int gx = 0; gx < gw; gx++) {
                int x0 = rect[0] + gx * cw / gw;
                int x1 = Math.max(x0 + 1, rect[0] + (gx + 1) * cw / gw);
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1 && y < rect[3]; y++) {
                    for (int x = x0; x < x1 && x < rect[2]; x++) {
                        int p = argb[y * w + x];
                        if ((p >>> 24) < OPAQUE_ALPHA) continue;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                int cell = gy * gw + gx;
                if (n > 0) {
                    out[cell] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
                    filled[cell] = true;
                }
            }
        }
        // Fill the holes from their row (left to right, then right to left), then from the rows above and below.
        for (int gy = 0; gy < gh; gy++) {
            int last = -1;
            for (int gx = 0; gx < gw; gx++) {
                int cell = gy * gw + gx;
                if (filled[cell]) last = cell;
                else if (last >= 0) { out[cell] = out[last]; filled[cell] = true; }
            }
            last = -1;
            for (int gx = gw - 1; gx >= 0; gx--) {
                int cell = gy * gw + gx;
                if (filled[cell]) last = cell;
                else if (last >= 0) { out[cell] = out[last]; filled[cell] = true; }
            }
        }
        for (int cell = 0; cell < gw * gh; cell++) {
            if (!filled[cell]) out[cell] = 0xFF000000;
        }
    }

    /** Makes the grid soft: {@code passes} times a box blur of 3 by 3 cells (the edges repeat their last cell). */
    static void blur(int[] img, int gw, int gh, int passes) {
        int[] tmp = new int[img.length];
        for (int pass = 0; pass < passes; pass++) {
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    tmp[y * gw + x] = average3(
                            img[y * gw + Math.max(0, x - 1)], img[y * gw + x], img[y * gw + Math.min(gw - 1, x + 1)]);
                }
            }
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    img[y * gw + x] = average3(
                            tmp[Math.max(0, y - 1) * gw + x], tmp[y * gw + x], tmp[Math.min(gh - 1, y + 1) * gw + x]);
                }
            }
        }
    }

    private static int average3(int a, int b, int c) {
        int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF) + 1) / 3;
        int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF) + 1) / 3;
        int bl = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + 1) / 3;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Moves {@code shown} towards {@code target} by {@code amount} (0 to 1) of the way, so the glow follows the video
     * without flickering. A channel that is not there yet always moves by at least one, so it does arrive.
     */
    static void follow(int[] shown, int[] target, float amount) {
        for (int i = 0; i < shown.length; i++) {
            shown[i] = 0xFF000000
                    | (step((shown[i] >> 16) & 0xFF, (target[i] >> 16) & 0xFF, amount) << 16)
                    | (step((shown[i] >> 8) & 0xFF, (target[i] >> 8) & 0xFF, amount) << 8)
                    | step(shown[i] & 0xFF, target[i] & 0xFF, amount);
        }
    }

    private static int step(int from, int to, float amount) {
        if (from == to) return to;
        int move = Math.round((to - from) * amount);
        if (move == 0) move = to > from ? 1 : -1;
        return Math.max(0, Math.min(255, from + move));
    }

    /**
     * True when the picture {left, top, right, bottom} (in the host's own pixels) leaves a gap of at least
     * {@code minGap} on any side of a host that is {@code hostW} by {@code hostH}: bars worth filling.
     */
    static boolean barsVisible(float hostW, float hostH, float left, float top, float right, float bottom,
                               float minGap) {
        if (right <= left || bottom <= top) return false;
        return Math.max(Math.max(top, hostH - bottom), Math.max(left, hostW - right)) >= minGap;
    }

    /** True for the id of a view that belongs to the Reels screen (Instagram calls Reels "clips"), not to Stories. */
    static boolean isReelsName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("stor")) return false;
        return n.contains("clips") || n.contains("reel");
    }

    /** True for the class name of a view that scrolls its children: a list or a pager is never a page's background. */
    static boolean isScrollerName(String className) {
        if (className == null) return false;
        return className.contains("RecyclerView")
                || className.contains("ViewPager")
                || className.contains("ScrollView")
                || className.contains("AbsListView")
                || className.contains("ListView");
    }
}
