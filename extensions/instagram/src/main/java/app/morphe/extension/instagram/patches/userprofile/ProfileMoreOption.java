/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.app.Dialog;
import android.content.DialogInterface;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.entity.ProfileInfo;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.crimera.ObjectBrowser;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.instagram.settings.PikoMenuSheet;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.entity.InstagramButton;
import app.morphe.extension.instagram.entity.InstagramButtonStyleEnum;
import app.morphe.extension.shared.ui.Dim;

import com.instagram.igds.components.button.IgdsButton;

public class ProfileMoreOption {
    private static boolean DEBUG;

    static {
        DEBUG = Pref.pikoDebug();
    }

    private static final String[] OPTION_KEYS = {
            "piko_view_profile_picture",
            "piko_copy_username",
            "piko_copy_full_name",
            "piko_copy_user_id",
            "piko_copy_bio",
            "piko_copy_links_from_bio",
    };

    private static final int HUE_PICTURE = 0xFFFF5C9E;
    private static final int HUE_USERNAME = 0xFF3B9EFF;
    private static final int HUE_FULL_NAME = 0xFF8B6CFF;
    private static final int HUE_USER_ID = 0xFFFFA33B;
    private static final int HUE_BIO = 0xFF2EC4B6;
    private static final int HUE_LINKS = 0xFF8CC63F;
    private static final int HUE_DEBUG = 0xFFFF6B5E;

    /**
     * The "More profile options" menu: View profile picture, the Copy options and, with debug on,
     * the object browser. It opens as the same card sheet as the Piko menu; if that sheet cannot be
     * shown the plain dialog is used, so the options are never lost.
     */
    public static void moreOptionsDailogueBox(Context context, UserData userData, Callable<String> refreshUrl) {
        try {
            if (showAsSheet(context, userData, refreshUrl)) {
                return;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Error at moreOptionsDailogueBox sheet", t);
        }
        plainDialog(context, userData, refreshUrl);
    }

    private static boolean showAsSheet(Context context, UserData userData, Callable<String> refreshUrl) {
        ArrayList<PikoMenuSheet.Option> cards = new ArrayList<>();
        cards.add(card(HUE_PICTURE, "instagram_photo_outline_24", null, "piko_view_profile_picture",
                null, true, context, userData, refreshUrl));
        cards.add(card(HUE_USERNAME, null, "@", "piko_copy_username",
                withAt(valueOf(() -> userData.getUsername())), false, context, userData, refreshUrl));
        cards.add(card(HUE_FULL_NAME, null, "Aa", "piko_copy_full_name",
                valueOf(() -> userData.getFullName()), false, context, userData, refreshUrl));
        cards.add(card(HUE_USER_ID, null, "#", "piko_copy_user_id",
                valueOf(() -> userData.getUserId()), false, context, userData, refreshUrl));
        cards.add(card(HUE_BIO, UI.DRAWABLE_INFO_ICON, null, "piko_copy_bio",
                valueOf(() -> userData.getBio()), false, context, userData, refreshUrl));
        cards.add(card(HUE_LINKS, UI.DRAWABLE_LINK_ICON, null, "piko_copy_links_from_bio",
                null, false, context, userData, refreshUrl));
        if (DEBUG) {
            cards.add(card(HUE_DEBUG, null, "</>", "piko_debug",
                    null, false, context, userData, refreshUrl));
        }
        return PikoMenuSheet.showOptions(context, str("piko_more_profile_options"), cards);
    }

    private static PikoMenuSheet.Option card(int hue, String drawable, String glyph, String key,
                                             String subtitle, boolean chevron, Context context,
                                             UserData userData, Callable<String> refreshUrl) {
        return new PikoMenuSheet.Option(hue, drawable, glyph, str(key), subtitle, chevron,
                () -> perform(key, context, userData, refreshUrl));
    }

    /** A value shown under a card's title: one line, or null when there is nothing to show. */
    private static String valueOf(Callable<String> reader) {
        try {
            String value = reader.call();
            if (value == null) return null;
            value = value.replaceAll("\\s+", " ").trim();
            return value.isEmpty() ? null : value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String withAt(String username) {
        return username == null ? null : "@" + username;
    }

    private static void plainDialog(Context context, UserData userData, Callable<String> refreshUrl) {
        try {
            InstagramDialogBox dialog = new InstagramDialogBox(context);

            ArrayList<String> keys = new ArrayList<>(Arrays.asList(OPTION_KEYS));
            if (DEBUG) keys.add("piko_debug");
            CharSequence[] items = new CharSequence[keys.size()];
            for (int i = 0; i < items.length; i++) items[i] = str(keys.get(i));

            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    perform(keys.get(which), context, userData, refreshUrl);
                }
            });
            dialog.setTitle(str("piko_more_profile_options"));
            dialog.setNegativeButton(str("piko_close"), (d, which) -> d.dismiss());
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            Dialog dlg = dialog.getDialog();
            dlg.show();
        } catch (Exception e) {
            Logger.printException(() -> "Error at moreOptionsDailogueBox", e);
            Utils.showToastShort(e.getMessage());
        }
    }

    /** Runs one option. {@code key} is the string name of the option's label. */
    private static void perform(String key, Context context, UserData userData, Callable<String> refreshUrl) {
        try {
            boolean toCopy = false;
            String text = null;

            switch (key) {
                case "piko_copy_username":
                    text = userData.getUsername();
                    toCopy = true;
                    break;
                case "piko_copy_full_name":
                    text = userData.getFullName();
                    toCopy = true;
                    break;
                case "piko_copy_user_id":
                    text = userData.getUserId();
                    toCopy = true;
                    break;
                case "piko_copy_bio":
                    text = userData.getBio();
                    toCopy = true;
                    break;
                case "piko_copy_links_from_bio":
                    // Links live in the bio as plain text; the profile object
                    // carries no separate list of them.
                    String links = linksFrom(userData.getBio());
                    if (links == null) {
                        Utils.showToastShort(str("piko_no_links_in_bio"));
                    } else {
                        text = links;
                        toCopy = true;
                    }
                    break;
                case "piko_view_profile_picture":
                    ProfilePictureViewer.show(context, userData, refreshUrl);
                    break;
                case "piko_debug":
                    // Using userData as it will be easier to debug known functions.
                    ObjectBrowser.browseObject(context, userData);
                    break;
                default:
                    break;
            }

            if (toCopy && text != null && text.length() > 0) {
                Utils.setClipboard(text);
                Utils.showToastShort(str("piko_copied"));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Error at moreOptionsDailogueBox onclick", e);
            Utils.showToastShort(e.getMessage());
        }
    }

    /**
     * Every link written in a bio, one per line. Instagram stores the bio as plain
     * text, so a URL there is only ever a URL by how it reads — matched loosely on
     * purpose, since most bios write "example.com" without a scheme.
     */
    private static String linksFrom(String bio) {
        if (bio == null || bio.isEmpty()) return null;
        Matcher matcher = BIO_LINK.matcher(bio);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String link = matcher.group();
            if (out.indexOf(link) >= 0) continue; // the same link twice reads as noise
            if (out.length() > 0) out.append('\n');
            out.append(link);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static final Pattern BIO_LINK = Pattern.compile(
            "(?:https?://)?(?:[\\w-]+\\.)+[a-z]{2,}(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE);

    public static void addProfileMoreOptionsButton(ViewGroup viewGroup, ProfileInfo profileInfo) {
        try {
            UserData userData = profileInfo.getUserData();

            Context context = viewGroup.getContext();
            InstagramButton button = new InstagramButton(context);
            button.setText(str("piko_more_profile_options"));
            button.setStyle(InstagramButtonStyleEnum.SECONDARY);
            button.setOnClickListener(() ->
                    moreOptionsDailogueBox(context, userData, () -> userData.refreshProfilePictureUrl(profileInfo.getUserSession()))
            );

            int marginPx = Dim.dp12;
            button.setMargins(marginPx, Dim.dp6, marginPx, Dim.dp8);

            IgdsButton igdsButton = button.getIgdsButton();
            viewGroup.addView(igdsButton);
            igdsButton.bringToFront();
            viewGroup.requestLayout();
            viewGroup.invalidate();
        } catch (Exception e) {
            Logger.printException(() -> "Failed to add profile more button: ", e);
        }
    }
}
