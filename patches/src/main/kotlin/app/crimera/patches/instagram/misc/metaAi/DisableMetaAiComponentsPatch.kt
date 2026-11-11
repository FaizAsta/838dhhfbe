/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.metaAi

import app.crimera.patches.instagram.utils.Constants
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.util.logging.Logger

/*
 * Disable Meta AI components
 *
 * Manifest components whose class name contains one of META_AI_NAME_PARTS are DISABLED
 * (android:enabled="false"): they stay declared but Android never starts them.
 * Nothing else is removed from the APK (no languages, analytics, permissions or updater).
 *
 * The names were not checked against the real manifest, so a part that matches nothing changes
 * nothing. Every match is logged: read the build log to see what was touched.
 *
 * Works on any Instagram version: components are found by name in the manifest of the APK being patched.
 */

private val META_AI_NAME_PARTS = listOf("metaai", "meta_ai")

private val COMPONENT_TAGS = listOf("service", "receiver", "provider", "activity", "activity-alias")

private val logger = Logger.getLogger("DisableMetaAiComponentsPatch")

@Suppress("unused")
val disableMetaAiComponentsPatch = resourcePatch(
    name = "Disable Meta AI components",
    description = "Disables Meta AI components in the manifest and turns Meta AI off everywhere in the app.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_INSTAGRAM)

    // Turns Meta AI off in the UI everywhere (reels, posts, comments, messages, search,
    // share sheet, in-app browser) through Instagram's feature flags.
    dependsOn(disableMetaAiPatch)

    execute {
        document("AndroidManifest.xml").use { doc ->
            val application = doc.getElementsByTagName("application").item(0) as Element
            var disabled = 0

            for (tag in COMPONENT_TAGS) {
                val nodes = application.getElementsByTagName(tag)
                // NodeList is live, so copy before changing anything.
                (0 until nodes.length)
                    .map { nodes.item(it) as Element }
                    .filter { element ->
                        val name = element.getAttribute("android:name")
                        META_AI_NAME_PARTS.any { part -> name.contains(part, ignoreCase = true) }
                    }.forEach {
                        logger.info("Disable Meta AI components: disabled $tag ${it.getAttribute("android:name")}")
                        it.setAttribute("android:enabled", "false")
                        disabled++
                    }
            }

            logger.info("Disable Meta AI components: disabled $disabled manifest entries")
        }
    }
}
