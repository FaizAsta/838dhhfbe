/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.devFlags;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.DeveloperOptions;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.entity.DeveloperOptionsItem;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.settings.SettingsStatus;

public class HookFlags {
    private static Map<String, Boolean> BOOL_FLAGS = new HashMap<>();
    private static Map<String, Long> LONG_FLAGS = new HashMap<>();
    private static DeveloperOptions developerOptions = new DeveloperOptions();

    private static void simpleOverflowMenuFlags() {
        BOOL_FLAGS.put("104772", false); //ig_ini
        BOOL_FLAGS.put("117613::0", true); //ig_overflow_menu_icon::use_more_lines_icon
        BOOL_FLAGS.put("100002", true); //ig_igds_android_prism_overflow_sheet
    }

    private static void adsFlags() {
//        BOOL_FLAGS.put("58206::0", false); //is_acp_enabled
//        BOOL_FLAGS.put("72396::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::9", false); //enable_no_invalidation_reason_for_mae_exclusion
//        BOOL_FLAGS.put("79181::0", false); //ig_reels_ads_1x2_explore_halc_android::is_enabled
        BOOL_FLAGS.put("110800::0", false); //ig_android_controller_migration::use_v2_controller Removed in version 435.0.0.0.2
        BOOL_FLAGS.put("114983", false); //ig_stories_restyle_midcard
        BOOL_FLAGS.put("95150", false); //ig_stories_music_midcard
        BOOL_FLAGS.put("84366::12", false); //ig_stories_ayt_midcard::enable_add_yours
        BOOL_FLAGS.put("120110", false); //ig_android_scroll_break
        BOOL_FLAGS.put("105778", false); //ig_android_restyle_post_cap_promo_dialog
    }

    // Thanks to @brosssh
    private static void suggestedContentFlags() {
        BOOL_FLAGS.put("111509::3", false); //ig_search_ta_nullstate_suggestions::is_android_enabled
        BOOL_FLAGS.put("82771::0", false); //igx_foundation_litho_stories_tray::is_litho_stories_tray_enabled
//        BOOL_FLAGS.put("109730", false); //ig_android_ai_discovery_menu
//        BOOL_FLAGS.put("80654", false); //ig_meta_ai_cdd_reels_viewer
    }

    // Meta AI. Always applied: Meta AI is permanently off, there is no switch for it.
    // Every entry belongs to a Meta AI surface. A key without "::" overrides the whole config, a
    // "id::param" key just that one value. Values are false (feature off) except the "remove_*" flags,
    // which hide an AI entry when true.
    private static void metaAiFlags() {
        // Reels, feed and comments.
        BOOL_FLAGS.put("109730", false); //ig_android_ai_discovery_menu
        BOOL_FLAGS.put("80654", false); //ig_meta_ai_cdd_reels_viewer
        BOOL_FLAGS.put("84760", false); //ig_meta_ai_cdd_feed
        BOOL_FLAGS.put("78970", false); //ig_meta_ai_cdd_comments_sheet
        BOOL_FLAGS.put("95528", false); //ig_meta_ai_cdd_async_fetch_android
        BOOL_FLAGS.put("110316", false); //ig_meta_ai_mentions
        BOOL_FLAGS.put("99585::83", false); //ig4a_reels_caption_learn_more::is_ai_summary_in_comments_enabled
        BOOL_FLAGS.put("99585::85", false); //ig4a_reels_caption_learn_more::replace_one_comment_with_ai_summary
        BOOL_FLAGS.put("85051::0", false); //ig4a_nf_ai_summary::enable_summary
        BOOL_FLAGS.put("97473::0", false); //meta_ai_ig_location_ai_summary::is_enabled_on_map
        BOOL_FLAGS.put("77148::20", false); //p92_android_insights::enable_nav_meta_ai_button
        BOOL_FLAGS.put("125260::1", false); //p92_android_native_insights::enable_nav_meta_ai_button
        BOOL_FLAGS.put("125260::22", false); //p92_android_native_insights::enable_llm_summary_meta_ai_nux
        BOOL_FLAGS.put("120893", false); //ig_meta_ai_in_reels_stories_unit
        BOOL_FLAGS.put("71915", false); //meta_ai_reels_chaining
        BOOL_FLAGS.put("96517::0", false); //ig4a_concord_growth_2025::enable_meta_ai_app_attribution_on_clips
        BOOL_FLAGS.put("96517::1", false); //ig4a_concord_growth_2025::enable_meta_ai_app_attribution_on_profile
        BOOL_FLAGS.put("71628::1", false); //ig4a_ai_agents_story_sharing::create_image_with_meta_ai_cta_enabled
        BOOL_FLAGS.put("71628::2", false); //ig4a_ai_agents_story_sharing::chat_with_meta_ai_cta_enabled

        // Share sheet.
        BOOL_FLAGS.put("145349", false); //ig4a_meta_ai_share_tile
        BOOL_FLAGS.put("122162", false); //ig_android_meta_ai_2_share_sheet
        BOOL_FLAGS.put("83278", false); //meta_ai_media_share_sheet
        BOOL_FLAGS.put("77870", false); //ig_meta_ai_sharing_improvements_mc
        BOOL_FLAGS.put("96332::13", false); //ig_android_concord_sharing::enable_create_on_meta_ai_sticker_for_media
        BOOL_FLAGS.put("96332::14", false); //ig_android_concord_sharing::enable_try_preset_on_meta_ai_sticker
        BOOL_FLAGS.put("96332::16", false); //ig_android_concord_sharing::enable_create_on_meta_ai_sticker_for_video

        // Direct messages.
        BOOL_FLAGS.put("70413", false); //ig_android_meta_ai_direct
        BOOL_FLAGS.put("70108", false); //igd_android_meta_ai_discovery
        BOOL_FLAGS.put("108911", false); //ig_android_meta_ai_v2
        BOOL_FLAGS.put("76479", false); //igd_metaai_composer_entrypoint
        BOOL_FLAGS.put("78699", false); //meta_ai_ig_no_space_invocation
        BOOL_FLAGS.put("75278", false); //ig_android_meta_ai_auto_popup
        BOOL_FLAGS.put("73643", false); //ig_ai_agents_discovery_retention_mimicry
        BOOL_FLAGS.put("91489", false); //igd_meta_ai_overflow_context_menu
        BOOL_FLAGS.put("78149", false); //igd_android_thread_meta_ai_improvements
        BOOL_FLAGS.put("59918", false); //ig_direct_ai_agents
        BOOL_FLAGS.put("71155", false); //ig_android_direct_ai_agent_chats
        BOOL_FLAGS.put("77059", false); //ig_ai_agents_group_chat_android
        BOOL_FLAGS.put("73638", false); //ig_direct_ai_agents_is_regenerate_enabled
        BOOL_FLAGS.put("89139", false); //igd_android_ai_button_rail
        BOOL_FLAGS.put("141553::0", false); //ig_android_direct_ai_pin_to_top_2026::enabled
        BOOL_FLAGS.put("95161::0", false); //ig_android_ai_foa_migration::is_home_enabled
        BOOL_FLAGS.put("87707::29", false); //ig_pandroid::is_direct_ai_chat_create_flow_modal_enabled
        BOOL_FLAGS.put("105426", false); //igd_android_kai
        BOOL_FLAGS.put("68355::0", false); //igd_android_write_with_ai_launcher::enable_feature
        BOOL_FLAGS.put("70841", false); //ig_android_ai_rewrite_launcher
        BOOL_FLAGS.put("78669", false); //igd_android_genai_new_messages_summary
        BOOL_FLAGS.put("82218", false); //ig_android_multimodal
        BOOL_FLAGS.put("115193::0", false); //gen_ai_suggested_reply_android::enabled
        BOOL_FLAGS.put("79677", false); //igd_meta_ai_preemptive_prefetch
        BOOL_FLAGS.put("118403::0", false); //igd_meta_ai_feedback_rail::is_enabled
        BOOL_FLAGS.put("86926::0", false); //igd_meta_ai_dr_suggested_prompts_fnf_thread::is_enabled
        BOOL_FLAGS.put("87528::0", false); //igd_meta_ai_style_edits::can_share_to_story_with_mimicry_sticker
        BOOL_FLAGS.put("96543::0", false); //igd_meta_ai_celebration_intent::android_enable_celebration_entrypoint
        BOOL_FLAGS.put("97608::0", false); //ig_meta_ai_header_attribution::is_wwai_enabled
        BOOL_FLAGS.put("77572::0", false); //ig_meta_ai_profile_context_menu_enabled::is_message_enabled
        BOOL_FLAGS.put("85865::0", false); //metaai_igd_android_deeplink::linkify_meta_ai_links
        BOOL_FLAGS.put("74175", false); //ig_android_meta_ai_voice
        BOOL_FLAGS.put("125961", false); //ig_android_meta_ai_voice_2_0
        BOOL_FLAGS.put("75925", false); //meta_ai_memu_onboarding
        BOOL_FLAGS.put("71935", false); //igd_android_meta_ai_memu_config
        BOOL_FLAGS.put("124353::7", false); //bcn_android_meta_ai_dm::enable_ask_meta_ai_post_upsell
        BOOL_FLAGS.put("116406", false); //p92_android_meta_ai_reply_bot

        // Search.
        BOOL_FLAGS.put("109855::26", true); //direct_android_core_messaging_26h1::remove_ai_pill
        BOOL_FLAGS.put("109855::32", true); //direct_android_core_messaging_26h1::creators_remove_ai_pill
        BOOL_FLAGS.put("109855::28", false); //direct_android_core_messaging_26h1::show_ai_prompts_in_suggested_pill
        BOOL_FLAGS.put("109855::34", false); //direct_android_core_messaging_26h1::creators_show_ai_prompts_in_suggested_pill
        BOOL_FLAGS.put("92518::2", true); //ig_client_direct_h1_2025_craft::android_remove_meta_ai_nullstate_prompts
        BOOL_FLAGS.put("92518::6", true); //ig_client_direct_h1_2025_craft::android_remove_meta_ai_voice_entry_point
        BOOL_FLAGS.put("92518::12", false); //ig_client_direct_h1_2025_craft::android_meta_ai_below_messages_serp
        BOOL_FLAGS.put("94946::21", true); //igd_android_inbox_search_experiments::remove_ask_meta_ai_prompts
        BOOL_FLAGS.put("94598::56", true); //ig_search_client_multi_turn_search::android_should_remove_meta_ai_donut
        BOOL_FLAGS.put("94598::135", false); //ig_search_client_multi_turn_search::is_android_large_ask_meta_ai_enabled
        BOOL_FLAGS.put("74933::17", false); //ig_client_search_meta_ai_integration_new::is_android_enabled
        BOOL_FLAGS.put("74933::36", false); //ig_client_search_meta_ai_integration_new::android_hcm_header_donut_enabled
        BOOL_FLAGS.put("138223", false); //p92_android_serp_ask_meta_ai
        BOOL_FLAGS.put("118797::7", false); //ig_android_serp_mvvm_2026::is_metai_ai_hcm_components_enabled
        BOOL_FLAGS.put("118797::39", false); //ig_android_serp_mvvm_2026::is_view_meta_ai_enabled
        BOOL_FLAGS.put("128853", false); //ig_ai_search_android_multi_turn_h2_26
        BOOL_FLAGS.put("87017::1", false); //ai_media_search::is_enabled
        BOOL_FLAGS.put("87017::2", false); //ai_media_search::is_profile_search_enabled
        BOOL_FLAGS.put("87017::3", false); //ai_media_search::is_profile_search_entry_point_enabled_for_all
        BOOL_FLAGS.put("62485", false); //igd_android_gen_ai_search_xstack
        BOOL_FLAGS.put("61103", false); //igd_android_gen_ai_search
        BOOL_FLAGS.put("62449::27", false); //android_igd_search_h2_2023::ai_ring_in_igd_search_bar_enabled
        BOOL_FLAGS.put("62449::28", false); //android_igd_search_h2_2023::ai_ring_in_igd_search_bar_logging_enabled
        BOOL_FLAGS.put("62449::45", false); //android_igd_search_h2_2023::show_meta_ai_nux_toast_igd_search
        BOOL_FLAGS.put("68131::2", false); //android_igd_search_h1_2024::meta_ai_null_state_prompts_enabled
        BOOL_FLAGS.put("68131::7", false); //android_igd_search_h1_2024::enable_retain_gen_ai_section
        BOOL_FLAGS.put("79018::1", false); //ig_foa_native_configs::meta_ai_fragment_based_navigation_enabled
        BOOL_FLAGS.put("79018::5", false); //ig_foa_native_configs::meta_ai_host_button_enabled

        // In-app browser.
        BOOL_FLAGS.put("112830::1", false); //iab_info_hub_js_ig::enable_ai_summaries_js
        BOOL_FLAGS.put("112830::35", false); //iab_info_hub_js_ig::enable_meta_ai_igd_bottom_sheet
        BOOL_FLAGS.put("112830::37", false); //iab_info_hub_js_ig::persist_ai_button_on_navigation
        BOOL_FLAGS.put("112830::40", false); //iab_info_hub_js_ig::enable_agentic_browser_winback
        BOOL_FLAGS.put("70334::23", false); //iab_android_ig_expandable_footer::use_unified_agentic_entry_point
        BOOL_FLAGS.put("93645::42", false); //ig_iab_browser_agent::enable_agentic_sheet_header_entrypoint
        BOOL_FLAGS.put("93645::49", false); //ig_iab_browser_agent::ig_iab_agentic_atc_enabled

        // Settings, upsells and app promos.
        BOOL_FLAGS.put("105320::0", false); //ig_meta_support_assistant_chat::ig_settings_android_meta_support_assistant_enabled
        BOOL_FLAGS.put("105320::3", false); //ig_meta_support_assistant_chat::ig_settings_search_android_meta_support_assistant_enabled
        BOOL_FLAGS.put("105320::10", false); //ig_meta_support_assistant_chat::ig_settings_maisa_entrypoint_in_tier_a_enabled
        BOOL_FLAGS.put("105320::16", false); //ig_meta_support_assistant_chat::enable_native_maisa_support_settings
        BOOL_FLAGS.put("105320::17", false); //ig_meta_support_assistant_chat::ig4a_native_maisa_thread_enabled
        BOOL_FLAGS.put("54584::54", false); //nme_ig_dev::enable_meta_ai_settings_entrypoint
        BOOL_FLAGS.put("64797::24", false); //nme_cross_support_dev::enable_meta_ai_settings_entrypoint
        BOOL_FLAGS.put("99726", false); //ig_settings_2_original_audio_reuse_on_meta_ai
        BOOL_FLAGS.put("99882", false); //ig_android_original_audio_reuse_on_meta_ai
        BOOL_FLAGS.put("88323", false); //ai_initiated_messaging_ig
        BOOL_FLAGS.put("82901", false); //igd_ai_memory_settings
        BOOL_FLAGS.put("82976", false); //igd_ai_connected_apps_settings
        BOOL_FLAGS.put("111123::1", false); //preloads_ig4a_entry_points::is_cross_universe_experiment_enabled_for_meta_ai
        BOOL_FLAGS.put("119736::6", false); //ig_search_meta_ai_upsell::is_android_meta_ai_app_upsell_enabled
        BOOL_FLAGS.put("121836::0", false); //igd_meta_ai_upsell::try_new_app_subtitle_enabled
        BOOL_FLAGS.put("97010::0", false); //igs2_meta_ai_app_top_position::is_enabled
        BOOL_FLAGS.put("130683::1", false); //ig_x_meta_zari::enable_meta_ai_zari
        BOOL_FLAGS.put("130683::2", false); //ig_x_meta_zari::enable_meta_ai_zari_android
        BOOL_FLAGS.put("70897::0", false); //ig_ugc_consumption_android::ai_home_enabled
        BOOL_FLAGS.put("70897::20", false); //ig_ugc_consumption_android::show_meta_ai_nav_tooltip

        // Imagine and AI creation tools.
        BOOL_FLAGS.put("65591", false); //ig_android_imagine_create
        BOOL_FLAGS.put("73957", false); //ig_android_imagine_stories
        BOOL_FLAGS.put("80115", false); //ig_android_imagine_stories_non_us
        BOOL_FLAGS.put("79859", false); //ig_android_mai_imagine
        BOOL_FLAGS.put("78099", false); //igd_android_imagine_w_igidv2
        BOOL_FLAGS.put("73825", false); //ig_direct_meta_ai_imagine
        BOOL_FLAGS.put("72519", false); //ig_android_meta_ai_intent_detection
        BOOL_FLAGS.put("76045", false); //ig_meta_ai_imagine_intent
        BOOL_FLAGS.put("86062", false); //ig_android_meta_ai_imagine_command_expansion
        BOOL_FLAGS.put("77696", false); //ig_android_imagine_personalization_launcher
        BOOL_FLAGS.put("96953", false); //concord_ig_android_imagine_upsell
        BOOL_FLAGS.put("93786", false); //igd_android_c50
        BOOL_FLAGS.put("74896", false); //ig4a_edit_with_ai_entrypoints
        BOOL_FLAGS.put("94663", false); //igd_android_ai_restyle
        BOOL_FLAGS.put("82374", false); //ig_android_genai_ai_filter
        BOOL_FLAGS.put("105390", false); //ig_stories_ai_transitions
        BOOL_FLAGS.put("105657", false); //ig_reels_ai_edit
        BOOL_FLAGS.put("81907", false); //ig_android_stories_ai_edit
        BOOL_FLAGS.put("105015", false); //ig_android_stories_restyle_effects_merge
        BOOL_FLAGS.put("119792", false); //ig_stories_ai_restyle_reveal_sticker
        BOOL_FLAGS.put("108777", false); //ig_android_genai_remix
        BOOL_FLAGS.put("108885", false); //ig_android_genai_kinetic_sand
        BOOL_FLAGS.put("61303::80", false); //ig_android_genai_magic_mod::is_expander_enabled
        BOOL_FLAGS.put("76608", false); //ig_android_sharing_genai_expander
        BOOL_FLAGS.put("96230::1", false); //igd_android_riff::is_restyle_action_enabled
        BOOL_FLAGS.put("123053", false); //igd_android_restyle_plus_plus
        BOOL_FLAGS.put("129876", false); //ig_stories_restyle_suggested_effect
        BOOL_FLAGS.put("88764::9", false); //ig_sharing_ai_voices::show_ai_filters_button_in_bottom_controls
        BOOL_FLAGS.put("89534::0", false); //ig_ai_fonts_android::is_ai_fonts_enabled
        BOOL_FLAGS.put("65740::8", false); //igd_sticker_suggestions::include_ai_stickers
        BOOL_FLAGS.put("78714::3", false); //igd_android_sticker_feature_dm_gating::open_dm_ai_stickers_enabled
        BOOL_FLAGS.put("130058::10", false); //ig_loot_android::ai_sticker_creation_enabled
        BOOL_FLAGS.put("112752::0", false); //ig_android_genai_rate_limiting::is_ig_photo_restyle_enabled
        BOOL_FLAGS.put("112752::1", false); //ig_android_genai_rate_limiting::is_ig_text_restyle_enabled
        BOOL_FLAGS.put("112752::2", false); //ig_android_genai_rate_limiting::is_ig_contextual_background_enabled
        BOOL_FLAGS.put("112752::3", false); //ig_android_genai_rate_limiting::is_ig_expander_enabled
        BOOL_FLAGS.put("112752::6", false); //ig_android_genai_rate_limiting::is_basel_animate_enabled
        BOOL_FLAGS.put("112752::10", false); //ig_android_genai_rate_limiting::is_basel_ai_transitions_enabled
        BOOL_FLAGS.put("112752::11", false); //ig_android_genai_rate_limiting::is_basel_clip_gen_enabled
        BOOL_FLAGS.put("112752::12", false); //ig_android_genai_rate_limiting::is_ig_transition_enabled
        BOOL_FLAGS.put("112752::13", false); //ig_android_genai_rate_limiting::is_voice_effects_enabled
        BOOL_FLAGS.put("112752::14", false); //ig_android_genai_rate_limiting::is_ig_faceswap_enabled
        BOOL_FLAGS.put("112752::16", false); //ig_android_genai_rate_limiting::is_igd_cutouts_enabled
        BOOL_FLAGS.put("112752::17", false); //ig_android_genai_rate_limiting::is_igd_music_gen_enabled
    }

    private static void profileActionBarFlags() {
        Set<String> pref = Pref.userProfileActionBarButtons();
        if (!pref.equals(Set.of(Constants.AB_CREATE))) {
            BOOL_FLAGS.put("81826::0", true); //igx_action_bar_service_replacement::is_profile_replaced
            BOOL_FLAGS.put("89230::0", true); //ig_android_profile_overflow_menu_redesign_launcher:enabled
        }
    }

    private static void mainFeedActionBarFlags() {
        Set<String> pref = Pref.mainFeedActionBarButtons();
        if (!pref.equals(Set.of(Constants.AB_CREATE, Constants.AB_NOTIFICATIONS))) {
            BOOL_FLAGS.put("81826::1", true); //igx_action_bar_service_replacement::is_main_feed_replaced
            BOOL_FLAGS.put("81826::4", true); //igx_action_bar_service_replacement::is_main_feed_large_screen_replaced
        }
    }

    // First-time tutorials and tooltips. Always applied: there is no switch for it.
    // Instagram only shows these when a server-controlled flag says so, so each one is answered
    // with "off" (the "suppression" flag with "on"). The ids are the ones in docs/mappings.
    private static void tutorialFlags() {
        BOOL_FLAGS.put("117194::0", true); //p92_new_user_tooltip_suppression::enabled
        BOOL_FLAGS.put("91296::1", false); //ig4a_switcher_education::enable_new_linked_account_tooltip
        BOOL_FLAGS.put("91296::2", false); //ig4a_switcher_education::enable_xapp_notif_tooltip
        BOOL_FLAGS.put("91296::3", false); //ig4a_switcher_education::enable_new_logged_in_tooltip
        BOOL_FLAGS.put("44067::0", false); //igd_pro_saved_reply_nux::nux_enabled
        BOOL_FLAGS.put("112940::0", false); //ig_android_aura_nux::story_viewer_list_nux_flow_enabled
        BOOL_FLAGS.put("112940::3", false); //ig_android_aura_nux::story_spotlight_nux_enabled
        // Show-at-most counters set to 0.
        LONG_FLAGS.put("81710::1", 0L); //fos_ig_ig4a_reels_education::educational_screen_max_impression
        LONG_FLAGS.put("81710::2", 0L); //fos_ig_ig4a_reels_education::educational_tooltip_max_impression
        LONG_FLAGS.put("49223::1", 0L); //igd_labyrinth_ui::onboarding_nux_limit
    }

    private static void employeeOptionsFlags() {
        if(Pref.enableEmployeeOptions()){
            BOOL_FLAGS.put("28538::0", true); //ig_android_employee_options::is_enabled
        }else{
            BOOL_FLAGS.put("28538::0", false); //ig_android_employee_options::is_enabled
        }
    }

    private static void analyticsFlags() {
        if (Pref.disableAnalytics()) {
            BOOL_FLAGS.put("29064", false); // ig_analytics2_consolidation
            LONG_FLAGS.put("29064::111", 0L); // ig_analytics2_consolidation::max_batch_lock_attempts
        }
    }

    // Instagram's "active pill": the rounded highlight behind the selected tab of the bottom bar. It is switched on
    // from the server by this MobileConfig flag (ig_android_modernization::enable_active_pills_on_tab_bar, present in
    // 439 and later), so turning it off here gives the stock bar back with only the icon changing on the selected tab.
    // Read when the bar is created, so a change of the setting needs a restart of the app.
    private static void selectedTabPillFlags() {
        // The custom pill of the floating bar replaces Instagram's own, so the two never show at once.
        if (Pref.removeSelectedTabPill() || Pref.customTabPill()) {
            BOOL_FLAGS.put("126411::0", false); //ig_android_modernization::enable_active_pills_on_tab_bar
        } else {
            BOOL_FLAGS.remove("126411::0");
        }
    }

    // Instagram's own floating tab bar. 452 ships it behind these two MobileConfig flags of the "modern UI edge to
    // edge" project: the floating tab bar itself, and edge-to-edge at the bottom of the main activity (the page runs
    // behind the bar, which is what a floating bar needs). Instagram draws and lays it out itself, so no strip is left
    // around it. Read when the bar is created, so a change of the setting needs a restart of the app.
    private static void nativeFloatingNavFlags() {
        if (Pref.nativeFloatingNav()) {
            BOOL_FLAGS.put("126024::12", true); //ig_android_modern_ui_edge_to_edge::is_floating_tab_bar_enabled
            BOOL_FLAGS.put("126024::2", true); //ig_android_modern_ui_edge_to_edge::is_bottom_enabled_for_main_activity
        } else {
            BOOL_FLAGS.remove("126024::12");
            BOOL_FLAGS.remove("126024::2");
        }
    }

    /**
     * True when the two flags of Instagram's own floating bar are on in the flags Instagram is answered with right
     * now, which is what it read when it created its bar (so a flag that was set by hand, and wins over the setting,
     * counts as well). The size of the floating bar (see FloatingNavBar) is only given to Instagram's bar when this
     * was true as the bar was found: switching the setting on does nothing to the bar that is already there until
     * the app is restarted.
     */
    public static boolean nativeFloatingNavActive() {
        return Boolean.TRUE.equals(BOOL_FLAGS.get("126024::12")) && Boolean.TRUE.equals(BOOL_FLAGS.get("126024::2"));
    }

    private static void addRecommendedFlags(){
        if(SettingsStatus.recommendedFlags) {
            Map<String, Boolean> recFlags = FlagsSharedPref.getAll();
            BOOL_FLAGS.putAll(recFlags);
            Map<String, Long> recLongFlags = FlagsSharedPref.getAllLong();
            LONG_FLAGS.putAll(recLongFlags);
        }
    }

    public static void load() {
        // First, so a flag chosen by hand in the developer options or the recommended flags still wins.
        selectedTabPillFlags();
        nativeFloatingNavFlags();
        addRecommendedFlags();
    }

    public static Boolean handleBoolFlags(long mobileConfigSpecifier) {
        try {
            DeveloperOptionsItem developerOptionsItem = new DeveloperOptionsItem(mobileConfigSpecifier);
            // Sometimes I want to block all the subflags inside a universal ID.
            // In which case I would only add the universal ID in the BOOL_MAP map.
            // If a boolean value is found then it will return else it will check for for the usual config ID
            String universalId = developerOptionsItem.getUniversalId();
            Boolean universalFlag = BOOL_FLAGS.getOrDefault(universalId, null);
            if(universalFlag!=null) return universalFlag;

            String configId = developerOptionsItem.getConfigId();
            return BOOL_FLAGS.getOrDefault(configId, null);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

    // mobileConfig has no dedicated int type, hence the integer-valued flags (counts, limits) are stored and returned.
    // as long instead of boolean
    public static Long handleLongFlags(long mobileConfigSpecifier) {
        try {
            DeveloperOptionsItem developerOptionsItem = new DeveloperOptionsItem(mobileConfigSpecifier);
            String universalId = developerOptionsItem.getUniversalId();
            Long universalFlag = LONG_FLAGS.getOrDefault(universalId, null);
            if(universalFlag!=null) return universalFlag;

            String configId = developerOptionsItem.getConfigId();
            return LONG_FLAGS.getOrDefault(configId, null);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

}
