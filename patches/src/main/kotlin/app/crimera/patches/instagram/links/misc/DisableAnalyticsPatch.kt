/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.links.misc

import app.crimera.patches.instagram.links.interceptUriPatch
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.crimera.patches.instagram.utils.addFlags
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

private object EventBuilderCommitFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(
        "EventBuilder was not acquired: BaseParameters null.",
        "Must call ejectBaseParameters and ejectExtraParameters before release",
    ),
    filters = listOf(
        string("Failed to serialize params"),
        // Event dispatch.
        methodCall(
            parameters = listOf("L"),
            returnType = "V",
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE),
        ),
    ),
)

@Suppress("unused")
val disableAnalyticsPatch =
    bytecodePatch(
        name = "Disable analytics",
        description = "Block analytics that are sent to Instagram/Facebook servers.",
        default = true,
    ) {
        dependsOn(settingsPatch, interceptUriPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // Skip event dispatch when analytics are disabled.
            // This prevents event delivery to the native logger queue.
            EventBuilderCommitFingerprint.method.apply {
                val dispatchInstructionIndex = EventBuilderCommitFingerprint.instructionMatches.last().index
                val dispatchInstruction = getInstruction(dispatchInstructionIndex)
                val freeRegister = findFreeRegister(dispatchInstructionIndex, dispatchInstruction.registersUsed)
                val nextInstruction = getInstruction(dispatchInstructionIndex + 1)

                addInstructionsWithLabels(
                    dispatchInstructionIndex,
                    """
                    $PREF_CALL_DESCRIPTOR->disableAnalytics()Z
                    move-result v$freeRegister
                    if-nez v$freeRegister, :piko_skip_dispatch
                    """.trimIndent(),
                    ExternalLabel("piko_skip_dispatch", nextInstruction),
                )
            }

            addFlags("analyticsFlags")
            enableSettings("disableAnalytics")
        }
    }

