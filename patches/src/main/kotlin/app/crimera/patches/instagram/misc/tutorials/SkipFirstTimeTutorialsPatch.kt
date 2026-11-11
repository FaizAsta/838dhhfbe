/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.tutorials

import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.addFlags
import app.crimera.patches.instagram.utils.fieldReference
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode

private const val TUTORIALS_DESCRIPTOR = "$PATCHES_DESCRIPTOR/Tutorials;"

private object IgBloksFullScreenOpenFingerprint : Fingerprint(
    returnType = "V",
    parameters =
        listOf(
            "Landroid/content/Context;",
            "Lcom/instagram/bloks/hosting/IgBloksScreenConfig;",
        ),
    strings = listOf("BKDataFetcher.fetch"),
    filters = listOf(
        // App id of the opened screen.
        fieldAccess(definingClass = "this", type = "Ljava/lang/String;", opcode = Opcode.IGET_OBJECT),
    ),
)

/**
 * Removes the first-time-user screens and tutorials.
 *
 *  1. The "Set up on new device" screens (contact import, location services) are Bloks screens, and
 *     every Bloks screen goes through one method that gets the screen's app id. It returns early
 *     for the ids that `Tutorials.shouldBlockOnboardingScreen` names, so the screen never opens.
 *     This used to be part of "Disable analytics" and only worked while that was switched on.
 *  2. The tooltips and "new user" education are only shown when a server-controlled flag says so,
 *     and the flag check is already hooked by `hookFlagsPatch`. `HookFlags.tutorialFlags` answers
 *     those checks with "off".
 *
 * There is no switch: it is always on. It has no name, so it is not listed as a patch and cannot be
 * switched off: it is applied together with "Add settings" (which depends on it).
 */
@Suppress("unused")
val skipFirstTimeTutorialsPatch =
    bytecodePatch(
        description =
            "Removes the \"Set up on new device\" screens and the first-time tutorials and tooltips.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        dependsOn(sharedExtensionPatch, hookFlagsPatch)

        execute {
            IgBloksFullScreenOpenFingerprint.let {
                val appIdField = it.instructionMatches.first().fieldReference

                it.method.addInstructionsWithLabels(
                    0,
                    """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $appIdField
                    invoke-static {v0}, $TUTORIALS_DESCRIPTOR->shouldBlockOnboardingScreen(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :piko_continue
                    return-void
                    """.trimIndent(),
                    ExternalLabel("piko_continue", it.method.getInstruction(0)),
                )
            }

            addFlags("tutorialFlags")
        }
    }
