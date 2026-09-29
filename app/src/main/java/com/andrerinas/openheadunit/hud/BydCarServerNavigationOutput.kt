package com.andrerinas.openheadunit.hud

import android.content.Context
import android.util.Log

/**
 * Test-only property-service experiment. This firmware rejects the app's writes with
 * code 20004 (signature-only BYDAUTO_INSTRUMENT_SET); a shell SDK experiment rendered.
 *
 * Profile membership does not grant access. The former asynchronous setMultiProperties
 * calls returned success before the permission check and even coalesced per-key calls.
 * Synchronous writes now surface that failure and disable this output for the process.
 */
internal class BydCarServerNavigationOutput(private val context: Context) {
    private var disabled = false
    private var showing = false
    private var logged = false

    fun update(icon: Int, exit: Int, distance: Int) {
        if (disabled || !(context.packageName.endsWith(".bydhudtest") || context.packageName.endsWith(".hudtest") || context.packageName.endsWith(".cn"))) return
        val turn = BydFactoryTurnCode.map(icon, exit) ?: run { clear(); return }
        if (distance !in 0..16777214) { clear(); return }
        try {
            require(BydCarServerTransport.write(context, STATE_USAGE_KEY, 2) == 0) { "state write rejected" }
            // Cleanup is needed even if a subsequent distance or turn write fails.
            showing = true
            require(BydCarServerTransport.write(context, DISTANCE_KEY, distance) == 0) { "distance write rejected" }
            require(BydCarServerTransport.write(context, TURN_ROAD_AHEAD_KEY, turn) == 0) { "turn write rejected" }
            if (!logged) {
                Log.i(TAG, "Car-server navigation writes completed turn=$turn distance=$distance (rendering unverified)")
                logged = true
            }
        } catch (error: Exception) {
            clear()
            disabled = true
            Log.w(TAG, "Car-server navigation disabled: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun clear() {
        if (!showing) return
        showing = false
        logged = false
        try {
            require(BydCarServerTransport.write(context, STATE_USAGE_KEY, 1) == 0) { "clear state rejected" }
            require(BydCarServerTransport.write(context, DISTANCE_KEY, 0) == 0) { "clear distance rejected" }
            require(BydCarServerTransport.write(context, TURN_ROAD_AHEAD_KEY, 11) == 0) { "clear turn rejected" }
            Log.i(TAG, "Car-server navigation clear writes completed")
        } catch (error: Exception) {
            disabled = true
            Log.w(TAG, "Car-server navigation cleanup failed: ${error.message}")
        }
    }

    companion object {
        private const val TAG = "BYD-CarServer-Navi"
        private const val STATE_USAGE_KEY = "0x4C212030"
        private const val DISTANCE_KEY = "0x43F01018"
        private const val TURN_ROAD_AHEAD_KEY = "0x43F01030"
    }
}
