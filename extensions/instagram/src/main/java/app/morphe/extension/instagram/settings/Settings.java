/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/
package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.settings.RestartPolicy.REQUIRED;
import static app.morphe.extension.instagram.settings.RestartPolicy.NOT_REQUIRED;
import app.morphe.extension.instagram.patches.download.DownloadFileNameFormatter;
import app.morphe.extension.instagram.patches.navigation.FloatingNavSize;

public class Settings {
    public static final BooleanSetting PIKO_DEBUG = new BooleanSetting("piko_debug", false, REQUIRED);
    public static final BooleanSetting MATERIAL_YOU_THEME = new BooleanSetting("material_you_theme", false, NOT_REQUIRED);
    public static final BooleanSetting AMOLED_THEME = new BooleanSetting("amoled_theme", false, NOT_REQUIRED);
    public static final StringSetting INSTAGRAM_THEME_MODE =
            new StringSetting("instagram_theme_mode", "-1", NOT_REQUIRED);
    public static final BooleanSetting INSTAGRAM_THEME_MODE_SYNCHRONIZED =
            new BooleanSetting("instagram_theme_mode_synchronized", false, NOT_REQUIRED);
    // It should be true always. It will be handled upon opening the piko settings for the first time.
    public static final BooleanSetting FIRST_TIME_PIKO = new BooleanSetting("first_time_piko", true, NOT_REQUIRED);

    public static final BooleanSetting DISABLE_ADS = new BooleanSetting("disable_ads", true, REQUIRED);
    public static final BooleanSetting OPEN_LINKS_EXTERNALLY = new BooleanSetting("open_links_externally", true, NOT_REQUIRED);
    public static final BooleanSetting SANITIZE_SHARE_LINKS = new BooleanSetting("sanitize_share_links", true, NOT_REQUIRED);
    public static final StringSetting CUSTOM_SHARING_DOMAIN = new StringSetting("custom_sharing_domain", "", NOT_REQUIRED);
    public static final BooleanSetting HIDE_SUGGESTED_CONTENT = new BooleanSetting("hide_suggested_content", true, REQUIRED);
    public static final BooleanSetting DEVELOPER_OPTIONS = new BooleanSetting("enable_developer_options", true, NOT_REQUIRED);
    public static final BooleanSetting DIRECTLY_OPEN_METACONFIG = new BooleanSetting("directly_open_metaconfig",true, NOT_REQUIRED);
    public static final BooleanSetting ENABLE_EMP_OPTIONS = new BooleanSetting("enable_employee_options",false, REQUIRED);
    public static final BooleanSetting ALLOW_USER_NETWORK_CERTIFICATE = new BooleanSetting("allow_user_network_certificate",false, REQUIRED);
    public static final BooleanSetting DISABLE_DISCOVER_PEOPLE = new BooleanSetting("disable_discover_people", true, REQUIRED);
    public static final BooleanSetting LIMIT_FOLLOWING_FEED = new BooleanSetting("limit_following_feed", false, REQUIRED);
    public static final BooleanSetting HIDE_FEED_POSTS = new BooleanSetting("hide_feed_posts", false, REQUIRED);
    public static final BooleanSetting DISABLE_ANALYTICS = new BooleanSetting("disable_analytics", false, REQUIRED);
    public static final BooleanSetting TURN_ON_ALL_GHOST_MODES = new BooleanSetting("turn_on_all_ghost_modes", false, NOT_REQUIRED);
    public static final BooleanSetting VIEW_STORIES_ANONYMOUSLY = new BooleanSetting("view_stories_anonymously", false, NOT_REQUIRED);
    public static final BooleanSetting VIEW_LIVE_ANONYMOUSLY = new BooleanSetting("view_live_anonymously", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_SCREENSHOT_DETECTION = new BooleanSetting("disable_screenshot_detection", false, NOT_REQUIRED);
    public static final BooleanSetting VIEW_DM_ANONYMOUSLY = new BooleanSetting("view_dm_anonymously", false, NOT_REQUIRED);
    public static final BooleanSetting MARK_AS_READ_AFTER_REPLY = new BooleanSetting("mark_as_read_after_reply", false, NOT_REQUIRED);
    public static final BooleanSetting INSTANTS_DOWNLOAD = new BooleanSetting("instants_download", false, NOT_REQUIRED);
    public static final BooleanSetting SAVE_DELETED_MESSAGES = new BooleanSetting("save_deleted_messages", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_STORIES = new BooleanSetting("disable_stories", false, REQUIRED);
    public static final BooleanSetting DISABLE_HIGHLIGHTS = new BooleanSetting("disable_highlights", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_STORIES_TRAY = new BooleanSetting("hide_stories_tray", false, REQUIRED);
    public static final BooleanSetting HIDE_HIDDEN_REQUESTS = new BooleanSetting("hide_hidden_requests", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_EXPLORE = new BooleanSetting("disable_explore", false, REQUIRED);
    public static final BooleanSetting DISABLE_COMMENTS = new BooleanSetting("disable_comments", false, REQUIRED);
    public static final BooleanSetting FOLLOW_BACK_INDICATOR = new BooleanSetting("follow_back_indicator", true, NOT_REQUIRED);
    public static final BooleanSetting FOLLOW_BACK_COLOR_INDICATOR = new BooleanSetting("follow_back_color_indicator", true, NOT_REQUIRED);
    public static final BooleanSetting FOLLOW_LIST_NON_FOLLOWER_BADGE = new BooleanSetting("follow_list_non_follower_badge", true, NOT_REQUIRED);
    public static final BooleanSetting VIEW_STORY_MENTIONS = new BooleanSetting("view_story_mentions", true, REQUIRED);
    public static final BooleanSetting DISABLE_STORY_FLIPPING = new BooleanSetting("disable_story_flipping", false, NOT_REQUIRED);
    public static final BooleanSetting LOOP_STORY = new BooleanSetting("loop_story", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_STORY_FOLLOW_BUTTON = new BooleanSetting("hide_story_follow_button", false, NOT_REQUIRED);
    public static final StringSetting CUSTOMISE_STORY_TIMESTAMP = new StringSetting("customise_story_timestamp", "default", REQUIRED);
    public static final BooleanSetting UNLIMITED_REPLAYS = new BooleanSetting("unlimited_replays", false, REQUIRED);
    public static final BooleanSetting DISABLE_DISAPPEARING_MESSAGES_GESTURE = new BooleanSetting("disable_disappearing_messages_gesture", false, REQUIRED);
    public static final BooleanSetting EPHEMERAL_MEDIA_CAPTION = new BooleanSetting("ephemeral_media_caption", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_LIKES = new BooleanSetting("hide_likes", false, REQUIRED);
    public static final BooleanSetting HIDE_SHARE_BUTTON = new BooleanSetting("hide_share_button", false, NOT_REQUIRED);
    public static final StringSetting HIDE_SAVE_BUTTON_SURFACES = new StringSetting("hide_save_button_surfaces", "", NOT_REQUIRED);
    // Pre-per-surface key; migrated once to the surfaces selection in Pref.
    public static final BooleanSetting HIDE_SAVE_BUTTONS_LEGACY = new BooleanSetting("hide_save_buttons", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_RESHARE_BUTTON = new BooleanSetting("hide_reshare_button", false, NOT_REQUIRED);
    public static final BooleanSetting INBOX_LOCK = new BooleanSetting("inbox_lock", false, REQUIRED);
    public static final BooleanSetting INBOX_LOCK_NOTIFICATIONS = new BooleanSetting("inbox_lock_notifications", false, NOT_REQUIRED);
    public static final BooleanSetting HIDDEN_CHATS = new BooleanSetting("hidden_chats", false, REQUIRED);
    public static final StringSetting HIDDEN_CHATS_TAPS = new StringSetting("hidden_chats_taps", "3", NOT_REQUIRED);
    public static final BooleanSetting HIDDEN_CHATS_MUTE = new BooleanSetting("hidden_chats_mute", true, NOT_REQUIRED);
    public static final BooleanSetting HIDE_REELS_FOLLOW_BUTTON = new BooleanSetting("hide_reels_follow_button", false, NOT_REQUIRED);
    public static final BooleanSetting IMPROVE_IMAGE_VIEWING = new BooleanSetting("improve_image_viewing", false, REQUIRED);
    public static final BooleanSetting RESUME_REELS_AFTER_SCROLLING = new BooleanSetting("resume_reels_after_scrolling", false, NOT_REQUIRED);
    public static final BooleanSetting COMMENT_COPY_BUTTON = new BooleanSetting("comment_copy_button", true, NOT_REQUIRED);
    public static final BooleanSetting COMMENT_SAVE_MEDIA_BUTTON = new BooleanSetting("comment_save_media_button", true, NOT_REQUIRED);
    public static final BooleanSetting HIDE_GROUP_CREATION_BUTTON_ON_SHARESHEET = new BooleanSetting("hide_group_creation_button_on_sharesheet", true, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_REELS_SCROLLING = new BooleanSetting("disable_reels_scrolling", false, REQUIRED);
    public static final BooleanSetting DISABLE_SWIPE_TO_CREATE = new BooleanSetting("disable_swipe_to_create", false, NOT_REQUIRED);
    public static final StringSetting FOCUS_LOCK_DURATION_MINUTES = new StringSetting("focus_lock_duration_minutes", "10080", NOT_REQUIRED);
    // Epoch millis. "0" means not locked.
    public static final StringSetting FOCUS_LOCK_UNTIL = new StringSetting("focus_lock_until", "0", NOT_REQUIRED);
    // Layout the stored lock was written with, so an older one can be retired. See FocusLock.
    public static final StringSetting FOCUS_LOCK_FORMAT = new StringSetting("focus_lock_format", "", NOT_REQUIRED);
    // Epoch millis of the pending unlock request. "0" means none.
    public static final StringSetting FOCUS_LOCK_UNLOCK_REQUESTED_AT = new StringSetting("focus_lock_unlock_requested_at", "0", NOT_REQUIRED);
    public static final BooleanSetting REMOVE_EMPTY_BOTTOM_SPACE = new BooleanSetting("remove_empty_bottom_space", true, REQUIRED);
    public static final BooleanSetting DISABLE_TYPING_STATUS = new BooleanSetting("disable_typing_status", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NOTES_TRAY = new BooleanSetting("hide_notes_tray", false, REQUIRED);
    public static final BooleanSetting DISABLE_VIDEO_AUTOPLAY = new BooleanSetting("disable_video_autoplay", false, NOT_REQUIRED);
    public static final BooleanSetting UNLOCK_PLUS_BENEFITS = new BooleanSetting("unlock_plus_benefits", false, NOT_REQUIRED);
    public static final StringSetting CHANGE_LIKE_ANIMATION = new StringSetting("change_like_animation", "ARES_LIKE_ACTIVATION", REQUIRED);
    public static final StringSetting CUSTOMISE_STORY_RING_SIZE = new StringSetting("customise_story_ring_size", "100", REQUIRED);
    public static final BooleanSetting ENABLE_MORE_OPTIONS_ON_POST = new BooleanSetting("enable_more_option_on_post", true, NOT_REQUIRED);
    public static final BooleanSetting USE_SYSTEM_FONT = new BooleanSetting("use_system_font", false, REQUIRED);

    public static final BooleanSetting DISABLE_DOUBLE_TAP_LIKE = new BooleanSetting("disable_double_tap_like", false, NOT_REQUIRED);

    public static final BooleanSetting ENABLE_DOWNLOAD = new BooleanSetting("enable_download", true, REQUIRED);
    public static final BooleanSetting FEED_DOWNLOAD_BUTTON = new BooleanSetting("feed_download_button", true, NOT_REQUIRED);
    public static final BooleanSetting REEL_DOWNLOAD_BUTTON = new BooleanSetting("reel_download_button", true, NOT_REQUIRED);
    public static final BooleanSetting STORY_DOWNLOAD_BUTTON = new BooleanSetting("story_download_button", true, NOT_REQUIRED);
    public static final BooleanSetting ENABLE_DIRECT_DOWNLOAD = new BooleanSetting("enable_direct_download", false, REQUIRED);
    public static final BooleanSetting DOWNLOAD_USERNAME_FOLDER = new BooleanSetting("download_username_folder", false, NOT_REQUIRED);
    public static final BooleanSetting EMBED_DOWNLOAD_METADATA = new BooleanSetting("embed_download_metadata", false, NOT_REQUIRED);
    public static final BooleanSetting PREFER_DASH_FORMAT = new BooleanSetting("prefer_dash_format", true, NOT_REQUIRED);
    public static final BooleanSetting DASH_AUDIO_AAC_LC = new BooleanSetting("dash_audio_aac_lc", false, NOT_REQUIRED);
    public static final StringSetting DOWNLOAD_FILE_NAME_TEMPLATE =
            new StringSetting("download_file_name_template", DownloadFileNameFormatter.DEFAULT_TEMPLATE, NOT_REQUIRED);
    // Should be kept empty by default as its handled in `StorageUtils.java`
    public static final StringSetting CUSTOM_DOWNLOAD_PATH = new StringSetting("custom_download_path", "", NOT_REQUIRED);
    public static final StringSetting EXTERNAL_DOWNLOADER_PACKAGE_NAME = new StringSetting("external_downloader_package_name", "", NOT_REQUIRED);
    public static final BooleanSetting DOWNLOAD_WITH_EXTERNAL_DOWNLOADER = new BooleanSetting("download_with_external_downloader", true, NOT_REQUIRED);

    public static final BooleanSetting HIDE_NAVIGATION_FEED = new BooleanSetting("hide_navigation_feed", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_REELS = new BooleanSetting("hide_navigation_reels", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_DIRECT = new BooleanSetting("hide_navigation_direct", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_SEARCH = new BooleanSetting("hide_navigation_search", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_CREATE = new BooleanSetting("hide_navigation_create", false, NOT_REQUIRED);
    public static final StringSetting NAVIGATION_TABS = new StringSetting("navigation_tabs", "", REQUIRED);
    // Floating pill instead of the stock bottom bar. Applies as soon as you go back to the app (no restart).
    // See FloatingNavBar.
    public static final BooleanSetting FLOATING_NAV_BAR = new BooleanSetting("floating_nav_bar", false, NOT_REQUIRED);
    // Size of the floating pill, in dp: how far it keeps from each side of the screen (a larger value is a narrower
    // pill) and the air above and below it (a larger value is a lower pill). Chosen with the sliders in the Navigation
    // bar screen, which show them as a width and a height. See FloatingNavSize.
    public static final StringSetting FLOATING_NAV_SIDE_DP =
            new StringSetting("floating_nav_side_dp", String.valueOf(FloatingNavSize.SIDE_DEFAULT_DP), NOT_REQUIRED);
    public static final StringSetting FLOATING_NAV_GAP_DP =
            new StringSetting("floating_nav_gap_dp", String.valueOf(FloatingNavSize.GAP_DEFAULT_DP), NOT_REQUIRED);
    // The distance between the bottom of the screen and the floating bar, in dp (a larger value is a bar that floats
    // higher). Chosen with the third slider in the Navigation bar screen. See FloatingNavSize and BarBottomGap.
    public static final StringSetting FLOATING_NAV_BOTTOM_DP =
            new StringSetting("floating_nav_bottom_dp", String.valueOf(FloatingNavSize.BOTTOM_DEFAULT_DP), NOT_REQUIRED);
    // The highlight behind the selected tab of the floating navigation bar, drawn from a picture the user picks (or a
    // soft capsule until one is picked). It always has the size of the selected tab, so it follows the width and
    // height sliders above. Only for the floating navigation bar (FLOATING_NAV_BAR), not for Instagram's own floating
    // bar. Applies as soon as you go back to the app (no restart). While it is on, Instagram's own selected tab pill
    // is kept off as well (see HookFlags). See SelectedTabPill.
    public static final BooleanSetting CUSTOM_TAB_PILL = new BooleanSetting("custom_tab_pill", false, NOT_REQUIRED);
    // Slide the custom selected tab pill sideways (or hold it), move it to another tab and let go to open that tab. On by default,
    // and it only does anything while "Custom selected tab pill" is on. Applies as soon as you go back to the app.
    // See TabPillDrag.
    public static final BooleanSetting CUSTOM_TAB_PILL_DRAG = new BooleanSetting("custom_tab_pill_drag", true, NOT_REQUIRED);
    // Ambient glow on Reels, like ambient mode on YouTube: when a Reel does not fill the screen, the bars above and
    // below it are filled with a soft glow in the colours of the video. Off by default. Applies as soon as you go
    // back to the app. See ReelsAmbient.
    public static final BooleanSetting REELS_AMBIENT = new BooleanSetting("reels_ambient", false, NOT_REQUIRED);
    // Takes away the rounded pill behind the selected tab of the bottom bar by turning Instagram's
    // enable_active_pills_on_tab_bar flag off. On by default; the flag is read when the bar is created, so it needs a
    // restart. See HookFlags.
    public static final BooleanSetting REMOVE_SELECTED_TAB_PILL = new BooleanSetting("remove_selected_tab_pill", true, REQUIRED);
    // Instagram's own floating tab bar (experimental): turns on the flags Instagram 452 ships for it
    // (ig_android_modern_ui_edge_to_edge::is_floating_tab_bar_enabled and ::is_bottom_enabled_for_main_activity), so
    // Instagram itself runs the page behind the bar. Off by default. The flags are read when the bar is created, so it
    // needs a restart. The bar of FLOATING_NAV_BAR steps aside while this is on. See HookFlags.
    public static final BooleanSetting NATIVE_FLOATING_NAV = new BooleanSetting("native_floating_nav", false, REQUIRED);
    // Live blur behind Instagram's own floating bar (see NativeBarBlur): the page under the bar shows through it,
    // blurred. Needs Android 12 or newer. Only does something while NATIVE_FLOATING_NAV is on and the app has been
    // restarted since. Off by default. Applies as soon as you go back to the app (no restart).
    public static final BooleanSetting NATIVE_FLOATING_NAV_BLUR = new BooleanSetting("native_floating_nav_blur", false, NOT_REQUIRED);
    // How strong the blur behind Instagram's own floating bar is (0 to 100 %, 0 = no blur) and how see-through the
    // glass is (0 to 100 %, 0 = solid, 100 = no tint at all). Chosen with the two sliders under the blur switch.
    // Apply as soon as you go back to the app (no restart). See NativeBarBlur.
    public static final StringSetting NATIVE_FLOATING_NAV_BLUR_LEVEL =
            new StringSetting("native_floating_nav_blur_level", "50", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_TRANSPARENCY =
            new StringSetting("native_floating_nav_transparency", "50", NOT_REQUIRED);
    // Liquid glass on top of the blur (see LiquidGlassEffect): the rim of Instagram's own floating bar bends what is
    // behind it like the edge of thick glass, with a faint colour fringe and a light rim. Needs Android 13 (below that
    // the plain blur is drawn) and only does something while the blur is on. Off by default. Applies as soon as you go
    // back to the app (no restart).
    public static final BooleanSetting NATIVE_FLOATING_NAV_LIQUID = new BooleanSetting("native_floating_nav_liquid", false, NOT_REQUIRED);
    // How strongly the rim bends the page, 0 to 100 % (0 = no bend, only the rim light). Chosen with the slider under
    // the liquid glass switch.
    public static final StringSetting NATIVE_FLOATING_NAV_REFRACTION =
            new StringSetting("native_floating_nav_refraction", "60", NOT_REQUIRED);
    // The rest of the liquid glass playground (see LiquidGlassLook), each a whole percent chosen with a slider under
    // the refraction slider. Saturation and brightness are centred (50 = no change). Apply as soon as you go back to
    // the app (no restart).
    public static final StringSetting NATIVE_FLOATING_NAV_CHROMA =
            new StringSetting("native_floating_nav_chroma", "20", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_EDGE_HIGHLIGHT =
            new StringSetting("native_floating_nav_edge_highlight", "20", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_SPECULAR =
            new StringSetting("native_floating_nav_specular", "30", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_FRESNEL =
            new StringSetting("native_floating_nav_fresnel", "50", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_DISTORTION =
            new StringSetting("native_floating_nav_distortion", "0", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_CORNER_RADIUS =
            new StringSetting("native_floating_nav_corner_radius", "100", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_Z_RADIUS =
            new StringSetting("native_floating_nav_z_radius", "80", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_OPACITY =
            new StringSetting("native_floating_nav_opacity", "100", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_SATURATION =
            new StringSetting("native_floating_nav_saturation", "50", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_BRIGHTNESS =
            new StringSetting("native_floating_nav_brightness", "50", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_SHADOW_OPACITY =
            new StringSetting("native_floating_nav_shadow_opacity", "0", NOT_REQUIRED);
    public static final StringSetting NATIVE_FLOATING_NAV_SHADOW_SPREAD =
            new StringSetting("native_floating_nav_shadow_spread", "35", NOT_REQUIRED);
    // Bevel mode of the playground: off = biconvex pill (the rim bends the page), on = dome (a magnifier with a flat back).
    public static final BooleanSetting NATIVE_FLOATING_NAV_DOME = new BooleanSetting("native_floating_nav_dome", false, NOT_REQUIRED);
    // Hides the system's gesture line (the thin handle at the bottom of the screen) while a floating bar is on, by
    // hiding the system navigation bar of the app's main screen. Off by default: a swipe up from the bottom edge then
    // needs two swipes to go Home. Applies as soon as you go back to the app (no restart). See GestureLineHider.
    public static final BooleanSetting HIDE_GESTURE_LINE = new BooleanSetting("hide_gesture_line", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_HOME_CREATE_BUTTON = new BooleanSetting("hide_home_create_button", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_HOME_NOTIFICATIONS_BUTTON = new BooleanSetting("hide_home_notifications_button", false, NOT_REQUIRED);

    public static final StringSetting ACTION_BAR_MAIN_FEED = new StringSetting("action_bar_main_feed", "", REQUIRED);
    public static final StringSetting ACTION_BAR_USER_PROFILE = new StringSetting("action_bar_user_profile", "", REQUIRED);
    public static final StringSetting ACTION_BAR_CHAT = new StringSetting("action_bar_chat_buttons", "", REQUIRED);
    public static final StringSetting ACTION_BAR_INBOX = new StringSetting("action_bar_inbox", "", REQUIRED);
    // Hides every Piko settings button (the gear in each action bar and the button on your own profile page) and opens
    // the Piko settings menu with a long press on the Home tab instead. Needs a restart because the action bars that are
    // already on screen keep the gear until they are built again. See ActionBarPatch.onHomeLongPress.
    public static final BooleanSetting HOME_LONG_PRESS_PIKO_SETTINGS = new BooleanSetting("home_long_press_piko_settings", false, REQUIRED);

    public static final BooleanSetting ENABLE_MARK_CHAT_AS_READ = new BooleanSetting("enable_mark_chat_as_read", true, NOT_REQUIRED);

    public static final StringSetting FILTER_STORY_BY_TYPE = new StringSetting("filter_story_by_type", "", NOT_REQUIRED);
    public static final StringSetting FILTER_STORY_BY_USER_TYPE = new StringSetting("filter_story_by_user_type", "", NOT_REQUIRED);

    public static final StringSetting FILTER_STORY_MIN_STORY_ITEMS = new StringSetting("filter_story_min_story_items", "1", NOT_REQUIRED);
    public static final StringSetting FILTER_STORY_MAX_STORY_ITEMS = new StringSetting("filter_story_max_story_items", "9999", NOT_REQUIRED);
}
