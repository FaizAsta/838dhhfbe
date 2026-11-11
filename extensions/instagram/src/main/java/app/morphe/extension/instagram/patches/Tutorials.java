/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/

package app.morphe.extension.instagram.patches;

@SuppressWarnings("unused")
public class Tutorials {
    // "NDX" is Instagram's new-device experience: the "Set up on new device" screens that ask for
    // contacts (ndx.ci) and location (ndx.ls). The whole family is blocked.
    private static final String NDX_SCREEN_PREFIX = "com.bloks.www.bloks.ig.ndx.";

    // Called from the method that opens every Bloks screen, with the screen's app id.
    // True means the screen is not opened.
    public static boolean shouldBlockOnboardingScreen(String appId) {
        return appId != null && appId.startsWith(NDX_SCREEN_PREFIX);
    }
}
