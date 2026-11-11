/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import app.morphe.extension.shared.Logger;

/**
 * The highlight behind the selected tab of the floating navigation bar ({@link FloatingNavBar}) and of Instagram's own
 * floating bar ({@link NativeBarBlur}): a rounded pill, drawn
 * from an image the user picked (or, until one is picked, a soft translucent capsule), that always has the size of
 * the selected tab inside the bar.
 * <p>
 * Everything that has to agree between the real bar and the live preview in the settings lives here, so the two draw
 * the same thing:
 * <ul>
 *   <li><b>Where it goes</b> ({@link #bounds}): the selected tab's cell inside the floating bar, kept a little away
 *       from the bar's edge on every side. The distance is a share of the bar's height, so the highlight is a smaller
 *       capsule nested inside the bar's own ends. When the width or the height slider changes the bar, the cell and
 *       the bar change with it, and so does the highlight. Nothing about its size is stored.</li>
 *   <li><b>How the image is resized</b> ({@link Renderer}): the image is cut into three slices from left to right.
 *       The two ends keep their round shape and are scaled by the height of the highlight; only the middle is
 *       stretched to fill the width. A capsule-shaped image therefore stays a capsule at any width and height, where
 *       stretching the whole image would squash its ends.</li>
 *   <li><b>Where the image lives</b>: one picture, kept in the app's private storage as a PNG that is never bigger
 *       than {@link #MAX_SIDE_PX} on its longest side, so it is cheap to keep in memory and to draw.</li>
 *   <li><b>Which tab is selected</b> ({@link #findSelected}): read from the bar's tabs (the tab that Instagram marks
 *       as selected). Nothing is searched for in Instagram's code.</li>
 * </ul>
 */
public final class SelectedTabPill {
    /** What came of importing a picked picture, so the caller can say which way it went wrong. */
    public enum ImportResult {
        ADDED,
        NOT_AN_IMAGE,
        TOO_LARGE,
        FAILED,
    }

    /** The picture is scaled down to this on its longest side when it is stored. Far more than a bar ever needs. */
    public static final int MAX_SIDE_PX = 512;

    private static final String FILE_NAME = "custom_tab_pill.png";
    /** A picked file bigger than this is refused (a picture meant for a 40 dp pill is never this big). */
    private static final long MAX_PICKED_BYTES = 16L * 1024 * 1024;
    private static final int MIN_SIDE_PX = 4;

    /** The distance between the highlight and the bar's edge, as a share of the bar's height... */
    private static final float INSET_FRACTION = 0.11f;
    /** ...but never less than this, so a short bar still shows a gap. */
    private static final float MIN_INSET_DP = 2f;
    /** Smaller than this (px) it is not drawn at all. */
    private static final float MIN_DRAWN_PX = 8f;

    private static final Object LOCK = new Object();
    // The stored picture, read again only when the file changed (told apart by its size and time).
    private static Bitmap cachedImage;
    private static long cachedStamp = Long.MIN_VALUE;
    private static boolean loggedNoSelection;

    private SelectedTabPill() {}

    // ---------------------------------------------------------------------------------------------------------------
    // The picture
    // ---------------------------------------------------------------------------------------------------------------

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** Whether the user has picked a picture. */
    public static boolean hasImage(Context context) {
        File file = file(context);
        return file.isFile() && file.length() > 0;
    }

    /**
     * The stored picture, or null when none was picked (or it can no longer be read). It is read from storage once
     * and again only after it was replaced or deleted, so this is cheap to call often.
     */
    public static Bitmap image(Context context) {
        try {
            File file = file(context);
            long stamp = file.isFile() && file.length() > 0 ? file.length() * 31L + file.lastModified() : -1L;
            synchronized (LOCK) {
                if (stamp == cachedStamp) return cachedImage;
                // The old bitmap is not recycled: a frame may still be drawing it. The garbage collector has it.
                cachedImage = stamp < 0 ? null : BitmapFactory.decodeFile(file.getPath());
                cachedStamp = stamp;
                if (stamp >= 0 && cachedImage == null) {
                    Logger.printException(() -> "Selected tab pill: the stored picture could not be read");
                }
                return cachedImage;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "SelectedTabPill.image failed", t);
            return null;
        }
    }

    /** Deletes the picture, so the default capsule is drawn again. False when there was none or it was not deleted. */
    public static boolean delete(Context context) {
        File file = file(context);
        if (!file.exists()) return false;
        boolean deleted = file.delete();
        synchronized (LOCK) {
            cachedStamp = Long.MIN_VALUE;
        }
        return deleted;
    }

    /**
     * Stores a picked picture. It is copied to a temporary file first (so a file that turns out not to be a picture
     * never replaces a working one), checked, scaled down to {@link #MAX_SIDE_PX} and saved as a PNG (which keeps its
     * transparency). Call it off the main thread: the picture can come from a cloud-backed provider.
     */
    public static ImportResult importFrom(Context context, Uri uri) {
        File directory = context.getFilesDir();
        File raw = new File(directory, FILE_NAME + ".raw");
        File converted = new File(directory, FILE_NAME + ".tmp");
        Bitmap decoded = null;
        Bitmap scaled = null;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return ImportResult.FAILED;
                try (OutputStream out = new FileOutputStream(raw)) {
                    long total = 0;
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_PICKED_BYTES) return ImportResult.TOO_LARGE;
                        out.write(buffer, 0, read);
                    }
                }
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getPath(), bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width < MIN_SIDE_PX || height < MIN_SIDE_PX) return ImportResult.NOT_AN_IMAGE;

            // Let the decoder skip whole pixels while the picture is still more than twice what is kept.
            int sample = 1;
            while (width / (sample * 2) >= MAX_SIDE_PX && height / (sample * 2) >= MAX_SIDE_PX) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeFile(raw.getPath(), options);
            if (decoded == null) return ImportResult.NOT_AN_IMAGE;

            Bitmap toSave = decoded;
            int longest = Math.max(decoded.getWidth(), decoded.getHeight());
            if (longest > MAX_SIDE_PX) {
                float factor = MAX_SIDE_PX / (float) longest;
                int newWidth = Math.max(MIN_SIDE_PX, Math.round(decoded.getWidth() * factor));
                int newHeight = Math.max(MIN_SIDE_PX, Math.round(decoded.getHeight() * factor));
                scaled = Bitmap.createScaledBitmap(decoded, newWidth, newHeight, true);
                toSave = scaled;
            }

            try (OutputStream out = new FileOutputStream(converted)) {
                if (!toSave.compress(Bitmap.CompressFormat.PNG, 100, out)) return ImportResult.FAILED;
            }
            File destination = file(context);
            if (!converted.renameTo(destination)) {
                throw new IOException("Could not move the picture into place");
            }
            synchronized (LOCK) {
                cachedStamp = Long.MIN_VALUE;
            }
            return ImportResult.ADDED;
        } catch (Exception | OutOfMemoryError e) {
            Logger.printException(() -> "Failed to add the selected tab pill picture", e);
            return ImportResult.FAILED;
        } finally {
            if (scaled != null && scaled != decoded) scaled.recycle();
            if (decoded != null) decoded.recycle();
            if (raw.exists() && !raw.delete()) {
                Logger.printException(() -> "Failed to remove a temporary tab pill file");
            }
            if (converted.exists() && !converted.delete()) {
                Logger.printException(() -> "Failed to remove a partly written tab pill file");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Where it goes
    // ---------------------------------------------------------------------------------------------------------------

    /** The gap between the highlight and the bar's edge, in px, for a bar of this height. */
    public static float inset(float barHeightPx, float density) {
        return Math.max(MIN_INSET_DP * density, barHeightPx * INSET_FRACTION);
    }

    /**
     * Puts the highlight of one tab into {@code out}: the tab's cell ({@code cellLeft} to {@code cellRight}), kept
     * inside the floating bar ({@code barLeft}..{@code barBottom}) by {@link #inset}. Used by the real bar and by the
     * preview, so both follow the sliders the same way. False when the result is too small to draw.
     */
    public static boolean bounds(RectF out, float cellLeft, float cellRight,
                                 float barLeft, float barRight, float barTop, float barBottom, float density) {
        float inset = inset(barBottom - barTop, density);
        out.set(Math.max(cellLeft, barLeft) + inset, barTop + inset,
                Math.min(cellRight, barRight) - inset, barBottom - inset);
        return out.width() >= MIN_DRAWN_PX && out.height() >= MIN_DRAWN_PX;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Which tab is selected
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The tab that is selected among the direct children of {@code bar}: its index, with its left and right edge in
     * {@code cell[0]} and {@code cell[1]} (the bar's own coordinates). -1 when none can be told apart.
     * <p>
     * Instagram marks the open tab with the view's selected state. It is looked for in three steps, each used only
     * when the one before found no single tab: the tab itself is selected; something inside the tab is selected; the
     * tab or something inside it is activated. Two or more tabs found in one step count as "not known", never a
     * guess, so the highlight is simply not drawn rather than drawn on the wrong tab.
     */
    public static int findSelected(ViewGroup bar, int[] cell) {
        boolean anyLaidOut = false;
        for (int mode = 0; mode < 3; mode++) {
            int found = -1;
            int count = 0;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) continue;
                anyLaidOut = true;
                if (marked(child, mode, 0)) {
                    count++;
                    if (found < 0) found = i;
                }
            }
            if (count == 1) {
                View tab = bar.getChildAt(found);
                cell[0] = tab.getLeft();
                cell[1] = tab.getRight();
                return found;
            }
        }
        if (anyLaidOut && !loggedNoSelection) {
            loggedNoSelection = true;
            final int children = bar.getChildCount();
            Logger.printInfo(() -> "Selected tab pill: no single selected tab among the bar's " + children
                    + " children, the highlight is not drawn");
        }
        return -1;
    }

    private static boolean marked(View view, int mode, int depth) {
        switch (mode) {
            case 0:
                return view.isSelected();
            case 1:
                if (depth > 0 && view.isSelected()) return true;
                break;
            default:
                if (view.isActivated()) return true;
                break;
        }
        // Two levels below the tab are enough for an icon in a frame (a badge, a dot).
        if (mode != 0 && depth < 2 && view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (marked(group.getChildAt(i), mode, depth + 1)) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------------------------

    /** Draws the highlight into a rectangle: the picture in three slices, or the default capsule. */
    public static final class Renderer {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect source = new Rect();
        private final RectF target = new RectF();
        private final RectF ring = new RectF();

        public Renderer(float density) {
            fill.setStyle(Paint.Style.FILL);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, density));
        }

        /**
         * @param dst   where the highlight goes (see {@link SelectedTabPill#bounds})
         * @param image the picture, or null for the default capsule
         * @param dark  whether the bar is dark, for the colours of the default capsule
         */
        public void draw(Canvas canvas, RectF dst, Bitmap image, boolean dark) {
            if (dst.width() <= 0f || dst.height() <= 0f) return;
            if (image != null && !image.isRecycled() && image.getWidth() >= MIN_SIDE_PX
                    && image.getHeight() >= MIN_SIDE_PX) {
                drawImage(canvas, dst, image);
            } else {
                drawDefault(canvas, dst, dark);
            }
        }

        /** A soft capsule, a little lighter than the bar on a dark one and a little darker on a light one. */
        private void drawDefault(Canvas canvas, RectF dst, boolean dark) {
            float radius = dst.height() / 2f;
            fill.setColor(dark ? 0x2EFFFFFF : 0x1A000000);
            canvas.drawRoundRect(dst, radius, radius, fill);
            edge.setColor(dark ? 0x26FFFFFF : 0x14000000);
            float half = edge.getStrokeWidth() / 2f;
            ring.set(dst.left + half, dst.top + half, dst.right - half, dst.bottom - half);
            canvas.drawRoundRect(ring, radius - half, radius - half, edge);
        }

        /**
         * Three slices from left to right: both ends are scaled by the height ratio (so their roundness is kept at
         * any size) and the middle takes up the rest of the width. When the target is too narrow for two ends, the
         * whole picture is stretched instead.
         */
        private void drawImage(Canvas canvas, RectF dst, Bitmap image) {
            int width = image.getWidth();
            int height = image.getHeight();
            // The end of a capsule is half its height wide. At least two pixels are left for the middle.
            int cap = Math.max(1, Math.min(height / 2, (width - 2) / 2));
            float scale = dst.height() / height;
            float capWidth = cap * scale;
            // Rounded to whole pixels, so the slices meet exactly and no seam shows between them.
            float leftEdge = Math.round(dst.left + capWidth);
            float rightEdge = Math.round(dst.right - capWidth);

            if (width < 4 || leftEdge >= rightEdge) {
                source.set(0, 0, width, height);
                canvas.drawBitmap(image, source, dst, paint);
                return;
            }
            source.set(0, 0, cap, height);
            target.set(dst.left, dst.top, leftEdge, dst.bottom);
            canvas.drawBitmap(image, source, target, paint);

            source.set(cap, 0, width - cap, height);
            target.set(leftEdge, dst.top, rightEdge, dst.bottom);
            canvas.drawBitmap(image, source, target, paint);

            source.set(width - cap, 0, width, height);
            target.set(rightEdge, dst.top, dst.right, dst.bottom);
            canvas.drawBitmap(image, source, target, paint);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // For a bar whose background is a drawable that draws the pill itself
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * One line about the bar's children and whether each is marked as selected or activated, for the debug copy of
     * the bar and the log. {@code selected} is the tab that was found (-1: none).
     */
    static String describeSelection(ViewGroup bar, int selected) {
        int count = bar.getChildCount();
        StringBuilder text = new StringBuilder("Selected tab pill: ");
        if (selected >= 0) {
            text.append("tab ").append(selected).append(" of ").append(count).append(" is selected");
        } else {
            text.append("NO single selected tab among ").append(count).append(" children");
        }
        text.append(" [");
        int shown = Math.min(count, 8);
        for (int i = 0; i < shown; i++) {
            View child = bar.getChildAt(i);
            if (i > 0) text.append(", ");
            text.append(child.getClass().getSimpleName())
                    .append(child.isSelected() ? " sel" : "")
                    .append(child.isActivated() ? " act" : "")
                    .append(child.getVisibility() == View.VISIBLE ? "" : " hidden");
        }
        return text.append(']').toString();
    }

    /**
     * The highlight behind the selected tab. Both floating bars use it: the floating navigation bar ({@link
     * FloatingNavBar}, whose background is its pill) and Instagram's own floating bar ({@link NativeBarBlur}, whose
     * background draws the pill). It remembers which tab is selected, slides over when another one is, draws the
     * highlight inside the rectangle of the pill (the same place {@link #bounds}, the same picture {@link Renderer}
     * and the same slide of about 0.2 s for both), and can be picked up and dragged along the bar (see {@link
     * TabPillDrag}).
     * <p>
     * The drawable calls {@link #update} before every frame and {@link #draw} when it draws. The selected tab is
     * looked at in every frame, because a tab changing its selected state does not redraw the bar's background by
     * itself; {@link #update} says when something changed, so the drawable asks for a redraw only then (and while
     * the slide is going), never while the screen is still.
     */
    static final class Highlight {
        /** How long the highlight takes to slide from one tab to another. */
        private static final long SLIDE_MS = 220L;
        /**
         * How long the highlight stays on a tab it was dropped on while Instagram has not marked that tab as selected
         * yet (opening a tab takes a moment). After that it goes back to the tab Instagram says is selected.
         */
        private static final long DROP_HOLD_MS = 800L;
        /** A dragged highlight is a little bigger so it looks lifted: this part of its inset is given back. */
        private static final float LIFT = 0.6f;

        private final float density;
        private final Renderer renderer;
        private final RectF tabRect = new RectF();
        /** Where the highlight was drawn last, in the bar's coordinates: what a finger has to touch to pick it up. */
        private final RectF shown = new RectF();
        private boolean shownValid;
        /** Scratch for the selected tab's left and right edge, so a frame allocates nothing. */
        private final int[] cell = new int[2];

        private boolean on;
        private Bitmap image;
        /** The selected tab (an index among the bar's children), or -1 while none is known. */
        private int tabIndex = -1;
        /** The selected tab's cell in the bar, in px: where the highlight is going to. */
        private float tabLeft;
        private float tabRight;
        /** The cell the highlight was drawn in when a slide to another tab started, and when that began. */
        private float slideFromLeft;
        private float slideFromRight;
        private long slideStart = -1L;
        /** The cell the highlight was drawn in last (it is between two tabs while it slides). */
        private float drawnLeft;
        private float drawnRight;
        private boolean drawn;
        /** Which tab was last reported, so the report is written when it changes and not on every frame. */
        private int reportedIndex = Integer.MIN_VALUE;
        private String report;

        // Being dragged: the highlight is held by a finger instead of sitting on its tab.
        private boolean dragging;
        /** Where the finger took hold: the finger's x minus the highlight's centre, so it does not jump to it. */
        private float grab;
        private float dragCentre;
        /** The row the tabs fill, in the bar's coordinates: a dragged highlight does not leave it. */
        private float rowLeft;
        private float rowRight;
        /** A tab that was dropped on, which Instagram has not yet marked as selected (-1: none), and until when. */
        private int holdIndex = -1;
        private long holdUntil;

        Highlight(float density) {
            this.density = density;
            renderer = new Renderer(density);
        }

        /** What was found about the selected tab when it last changed, for the debug copy; null while it is off. */
        String report() {
            return report;
        }

        /** The selected tab as an index among the bar's children, or -1. */
        int selectedIndex() {
            return on ? tabIndex : -1;
        }

        boolean isDragging() {
            return dragging;
        }

        /**
         * Takes the settings (on or off, which picture) and looks at which tab is selected in {@code bar}. True when
         * something changed, so the drawable has to be drawn again.
         */
        boolean update(ViewGroup bar, boolean wanted, Bitmap picture) {
            boolean changed = false;
            if (on != wanted || image != picture) {
                on = wanted;
                image = picture;
                changed = true;
                if (!wanted) {
                    forget();
                    report = null;
                    reportedIndex = Integer.MIN_VALUE;
                }
            }
            if (!on || !bar.isLaidOut()) return changed;
            int selected = findSelected(bar, cell);
            if (selected != reportedIndex) {
                reportedIndex = selected;
                report = describeSelection(bar, selected);
            }
            if (holdIndex >= 0) {
                if (selected == holdIndex || SystemClock.uptimeMillis() >= holdUntil) {
                    holdIndex = -1;
                } else {
                    // Instagram has not marked the tab that was dropped on as selected yet: the highlight stays on it.
                    return changed;
                }
            }
            if (select(selected, cell[0], cell[1])) changed = true;
            return changed;
        }

        private void forget() {
            tabIndex = -1;
            slideStart = -1L;
            drawn = false;
            shownValid = false;
            dragging = false;
            holdIndex = -1;
        }

        /**
         * The tab that is selected now (index -1: none is known) and its cell in the bar. A different tab than before
         * makes the highlight slide over to it; the same tab in a cell of another size (a slider was moved) makes it
         * jump to the new size at once, so the highlight never lags behind the bar. True when anything changed.
         */
        private boolean select(int index, float left, float right) {
            if (index < 0) {
                if (tabIndex < 0) return false;
                forget();
                return true;
            }
            if (index == tabIndex && left == tabLeft && right == tabRight) return false;
            if (tabIndex >= 0 && index != tabIndex && drawn) {
                slideFromLeft = drawnLeft;
                slideFromRight = drawnRight;
                slideStart = SystemClock.uptimeMillis();
            } else {
                slideStart = -1L;
            }
            tabIndex = index;
            tabLeft = left;
            tabRight = right;
            return true;
        }

        /** Moves the drawn cell along its slide. True while it is still sliding (another frame is needed). */
        private boolean advanceSlide() {
            if (slideStart < 0L) {
                drawnLeft = tabLeft;
                drawnRight = tabRight;
                return false;
            }
            float t = (SystemClock.uptimeMillis() - slideStart) / (float) SLIDE_MS;
            if (t >= 1f) {
                slideStart = -1L;
                drawnLeft = tabLeft;
                drawnRight = tabRight;
                return false;
            }
            // Fast at the start, settling at the end.
            float left = 1f - t;
            float eased = 1f - left * left * left;
            drawnLeft = slideFromLeft + (tabLeft - slideFromLeft) * eased;
            drawnRight = slideFromRight + (tabRight - slideFromRight) * eased;
            return true;
        }

        // -----------------------------------------------------------------------------------------------------------
        // Dragging
        // -----------------------------------------------------------------------------------------------------------

        /**
         * True when a touch at ({@code x}, {@code y}) in the bar's coordinates is on the highlight, where it was drawn
         * last ({@code slop} px around it count too). False while there is no highlight on screen or it is already
         * being dragged.
         */
        boolean hit(float x, float y, float slop) {
            return on && tabIndex >= 0 && !dragging && shownValid
                    && x >= shown.left - slop && x <= shown.right + slop
                    && y >= shown.top - slop && y <= shown.bottom + slop;
        }

        /** Picks the highlight up with a finger at {@code fingerX} (the bar's coordinates). */
        boolean beginDrag(ViewGroup bar, float fingerX) {
            if (!on || tabIndex < 0 || dragging || !drawn) return false;
            measureRow(bar);
            grab = fingerX - (drawnLeft + drawnRight) / 2f;
            dragCentre = fingerX - grab;
            dragging = true;
            // A slide that was still going stops where it is: the highlight is in the finger's hand now.
            slideStart = -1L;
            return true;
        }

        /** The finger moved to {@code fingerX}: the highlight follows it along the bar. */
        void dragTo(ViewGroup bar, float fingerX) {
            if (!dragging) return;
            measureRow(bar);
            dragCentre = fingerX - grab;
        }

        /** The finger let go over the tab it started on (or the drag was cancelled): the highlight slides back. */
        void endDrag() {
            if (!dragging) return;
            dragging = false;
            slideFromLeft = drawnLeft;
            slideFromRight = drawnRight;
            slideStart = SystemClock.uptimeMillis();
        }

        /**
         * The finger let go over another tab, which is now being opened: the highlight slides from where it is to
         * that tab and stays there until Instagram marks the tab as selected (or {@link #DROP_HOLD_MS} has passed),
         * so it does not slide back to the old tab while the new one opens.
         */
        void dropOn(int index, float left, float right) {
            if (!dragging) return;
            dragging = false;
            long now = SystemClock.uptimeMillis();
            slideFromLeft = drawnLeft;
            slideFromRight = drawnRight;
            slideStart = now;
            tabIndex = index;
            tabLeft = left;
            tabRight = right;
            holdIndex = index;
            holdUntil = now + DROP_HOLD_MS;
        }

        /** The row the tabs fill: from the left edge of the first visible tab to the right edge of the last one. */
        private void measureRow(ViewGroup bar) {
            float low = Float.MAX_VALUE;
            float high = -Float.MAX_VALUE;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) continue;
                low = Math.min(low, child.getLeft());
                high = Math.max(high, child.getRight());
            }
            if (high > low) {
                rowLeft = low;
                rowRight = high;
            }
        }

        /**
         * Draws the highlight inside the pill, which is the rectangle {@code barLeft}..{@code barBottom} in the
         * drawable's coordinates. True while the highlight is still sliding (the drawable has to be drawn again).
         */
        boolean draw(Canvas canvas, float barLeft, float barRight, float barTop, float barBottom, boolean dark) {
            shownValid = false;
            if (!on || tabIndex < 0) return false;
            boolean sliding = false;
            if (dragging) {
                float width = tabRight - tabLeft;
                float centre = dragCentre;
                if (rowRight - rowLeft > width) {
                    centre = Math.min(Math.max(centre, rowLeft + width / 2f), rowRight - width / 2f);
                }
                drawnLeft = centre - width / 2f;
                drawnRight = centre + width / 2f;
            } else {
                sliding = advanceSlide();
            }
            drawn = true;
            if (bounds(tabRect, drawnLeft, drawnRight, barLeft, barRight, barTop, barBottom, density)) {
                if (dragging) {
                    // Lifted: a little bigger than at rest, but still inside the pill.
                    float give = inset(barBottom - barTop, density) * LIFT;
                    tabRect.set(tabRect.left - give, tabRect.top - give, tabRect.right + give, tabRect.bottom + give);
                }
                shown.set(tabRect);
                shownValid = true;
                renderer.draw(canvas, tabRect, image, dark);
            }
            return sliding;
        }
    }
}
