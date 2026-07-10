package com.vayunmathur.headphones.service

import com.vayunmathur.headphones.protocol.CommandBuilders
import com.vayunmathur.headphones.protocol.EqBands
import com.vayunmathur.headphones.protocol.EqPreset
import com.vayunmathur.headphones.protocol.Feature
import com.vayunmathur.headphones.protocol.NcAsmMode
import com.vayunmathur.headphones.protocol.NcAsmState

/**
 * High-level command API used by the UI. Each method translates an intent into the appropriate
 * [CommandBuilders] message and hands it to [HeadphonesService] for sending. NC/ASM edits are
 * applied as deltas on top of the last-known state so partial changes (e.g. just the ambient
 * slider) preserve the other fields.
 */
object HeadphonesController {

    private val currentNcAsm: NcAsmState
        get() = HeadphonesService.state.value.ncAsm

    fun setMode(mode: NcAsmMode) {
        HeadphonesService.send(CommandBuilders.setNcAsm(currentNcAsm.copy(mode = mode)))
    }

    fun setAmbientLevel(level: Int) {
        HeadphonesService.send(
            CommandBuilders.setNcAsm(
                currentNcAsm.copy(mode = NcAsmMode.AMBIENT_SOUND, ambientLevel = level),
            ),
        )
    }

    fun setVoicePassthrough(enabled: Boolean) {
        HeadphonesService.send(CommandBuilders.setNcAsm(currentNcAsm.copy(voicePassthrough = enabled)))
    }

    fun setWindNoiseReduction(enabled: Boolean) {
        HeadphonesService.send(
            CommandBuilders.setNcAsm(
                currentNcAsm.copy(mode = NcAsmMode.NOISE_CANCELLING, windNoiseReduction = enabled),
            ),
        )
    }

    fun setEqPreset(preset: EqPreset) {
        HeadphonesService.send(CommandBuilders.setEqPreset(preset))
    }

    fun setEqBands(bands: EqBands) {
        HeadphonesService.send(CommandBuilders.setEqCustom(bands))
    }

    fun setFeature(feature: Feature, enabled: Boolean) {
        HeadphonesService.send(CommandBuilders.setFeature(feature, enabled))
    }

    /** Cancels a device's multipoint registration. */
    fun removePairedDevice(address: String) {
        HeadphonesService.send(CommandBuilders.removePairedDevice(address))
    }

    fun refresh() {
        HeadphonesService.send(CommandBuilders.getDualBattery())
        HeadphonesService.send(CommandBuilders.getCaseBattery())
        HeadphonesService.send(CommandBuilders.getNcAsm())
        HeadphonesService.send(CommandBuilders.getEq())
    }

    /** Sends an arbitrary raw payload (Debug Log). */
    fun sendRaw(payload: ByteArray) {
        HeadphonesService.send(CommandBuilders.raw(payload))
    }
}
