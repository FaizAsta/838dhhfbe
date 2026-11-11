/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.metaAi

import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.addFlags
import app.morphe.patcher.patch.bytecodePatch

/**
 * Turns Meta AI off for good through Instagram's own feature flags.
 *
 * Every Meta AI entry point (the Ask Meta AI cards on reels, posts and comments, the AI chats and
 * prompts in messages and search, the share sheet, the in-app browser, the image and video tools)
 * is only shown when a server-controlled flag says so, and the flag check is already hooked by
 * `hookFlagsPatch`. `HookFlags.metaAiFlags` answers those checks with "off", so nothing has to be
 * hidden afterwards. There is no switch: it is always on. The flags are read when the app starts.
 *
 * It has no name, so it is not listed as a patch and cannot be switched off: it is applied together
 * with "Add settings" (which depends on it).
 */
@Suppress("unused")
val disableMetaAiPatch =
    bytecodePatch(
        description =
            "Turns Meta AI off everywhere in the app: reels, posts, comments, messages, search, " +
                "the share sheet, the in-app browser and its AI creation tools.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        dependsOn(sharedExtensionPatch, hookFlagsPatch)

        execute {
            addFlags("metaAiFlags")
        }
    }
