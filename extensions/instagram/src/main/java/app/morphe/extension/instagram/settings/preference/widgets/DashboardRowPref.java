/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.content.Context;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;

/**
 * One row of the root settings dashboard, holding up to two category tiles.
 * Only used to display the root page; search indexes the plain {@link ButtonPref} rows instead.
 */
@SuppressWarnings("deprecation")
public class DashboardRowPref extends Preference {

    /** A category the tile opens. */
    public static final class Tile {
        final String title;
        final String key;
        final String iconName;

        public Tile(String title, String key, String iconName) {
            this.title = title == null ? "" : title;
            this.key = key;
            this.iconName = iconName;
        }
    }

    private final Tile first;
    private final Tile second;

    public DashboardRowPref(Context context, Tile first, Tile second) {
        super(context);
        this.first = first;
        this.second = second;
        // The tiles handle their own taps; the row itself is not selectable.
        setSelectable(false);
        setPersistent(false);
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        // The list may hand over a row recycled from another preference type.
        if (!(convertView instanceof DashboardStyle.TileRow)) {
            convertView = null;
        }
        return super.getView(convertView, parent);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        return DashboardStyle.createTileRow(getContext(), first, second);
    }

    @Override
    protected void onBindView(View view) {
        // Tile content is static, so there is nothing to bind.
    }
}
