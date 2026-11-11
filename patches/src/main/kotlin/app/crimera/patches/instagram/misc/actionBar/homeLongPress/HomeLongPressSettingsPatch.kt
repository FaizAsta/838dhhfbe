/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.actionBar.homeLongPress

import app.crimera.patches.instagram.misc.internalStuffs.developerOptions.HomeIconOnClickListenerFingerprint
import app.crimera.patches.instagram.utils.Constants.ACTIONBAR_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel

/**
 * Lets a long press on the Home tab open the Piko settings menu.
 *
 * Instagram already attaches a long click listener to the Home tab icon (the one the
 * "Unlock developer options" patch uses). This patch puts one call at the same spot, before
 * Instagram looks at the press: the extension opens the Piko settings menu and consumes the press
 * when the "Open Piko settings with Home long press" setting is on, and does nothing otherwise.
 *
 * This patch is a dependency of the settings patch, so it runs before "Unlock developer options".
 * That patch then inserts its own check at the same index, ahead of this one, and keeps working
 * (the extension turns developer options off while Home long press opens Piko settings).
 */
val homeLongPressSettingsPatch =
    bytecodePatch(
        description = "Opens Piko settings when the Home tab is long pressed, if the setting is on.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            HomeIconOnClickListenerFingerprint.method.apply {
                val matches = HomeIconOnClickListenerFingerprint.instructionMatches
                val insertIndex = matches[2].index
                val originalInstruction = getInstruction(insertIndex)
                val freeRegister = findFreeRegister(insertIndex)

                addInstructionsWithLabels(
                    insertIndex,
                    """
                    invoke-static {}, $ACTIONBAR_DESCRIPTOR->onHomeLongPress()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :not_piko_settings
                    const/16 v$freeRegister, 0x1
                    return v$freeRegister
                    """.trimIndent(),
                    ExternalLabel("not_piko_settings", originalInstruction),
                )
            }
        }
    }
