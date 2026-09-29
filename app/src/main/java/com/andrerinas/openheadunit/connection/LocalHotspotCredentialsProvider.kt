package com.andrerinas.openheadunit.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.ApInterfaceCandidate
import com.andrerinas.openheadunit.aap.LocalHotspotPolicy
import com.andrerinas.openheadunit.aap.NativeCredentialsPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executor

/** An app-owned local AP reservation; never changes saved Wi-Fi or global tethering settings. */
class LocalHotspotCredentialsProvider(private val context: Context, private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // All state changes, including framework callbacks and final publication, run on main.
    private var generation = 0
    private var requested = false
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var resolveJob: Job? = null
    private var timeout: Runnable? = null
    private var ready: Credentials? = null
    private var lastFailure: String? = null
    private var listener: ((String, String, String, String) -> Unit)? = null
    private var invalidated: (() -> Unit)? = null

    private class Credentials(val ssid: String, val password: String, val ip: String, val bssid: String)

    fun setCredentialsListener(value: (String, String, String, String) -> Unit) { listener = value }
    fun setInvalidatedListener(value: () -> Unit) { invalidated = value }

    fun start() { main.post {
        if (requested) { publish(); return@post }
        if (Build.VERSION.SDK_INT < 26) {
            report(context.getString(R.string.local_hotspot_requires_android_8))
            return@post
        }
                lastFailure = null
                requested = true
                val token = ++generation
                disconnectTwoPointFourStation()
                val before = interfaces().mapNotNull { it.siteLocalIpv4 }.toSet()
        val upstreams = upstreamInterfaces()
        timeout = Runnable {
            if (current(token)) fail(context.getString(R.string.local_hotspot_not_ready))
        }.also { main.postDelayed(it, 25_000) }
        try {
            AppLog.i("LocalHotspot: starting Android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} with Wi-Fi client enabled=${wifi.isWifiEnabled}")
            startFiveGhzHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    if (!current(token) || reservation != null) { value.close(); return }
                    reservation = value
                    resolve(token, value, before, upstreams)
                }
                override fun onStopped() {
                    if (current(token)) fail(context.getString(R.string.local_hotspot_stopped))
                }
                override fun onFailed(reason: Int) {
                    if (current(token)) fail(context.getString(R.string.local_hotspot_start_failed, reason))
                }
            })
        } catch (_: SecurityException) {
            fail(context.getString(R.string.local_hotspot_need_permissions))
        } catch (e: Exception) {
            AppLog.w("LocalHotspot: start failed: ${e.javaClass.simpleName}")
            fail(context.getString(R.string.local_hotspot_cannot_start_5ghz))
        }
    } }

    // On DiLink 5.0 / Android 12 the Qualcomm stack pins the local hotspot onto the car
    // Wi-Fi client's 2.4 GHz channel even when 5 GHz was requested, so the association
    // has to go first. Best-effort: where the platform ignores disconnect(), the 2.4 GHz
    // refusal message still names the Wi-Fi switch.
    @Suppress("DEPRECATION")
    private fun disconnectTwoPointFourStation() {
        if (Build.VERSION.SDK_INT !in 30..32) return
        val connection = runCatching { wifi.connectionInfo }.getOrNull() ?: return
        // The BSSID is masked for ordinary apps on BYD builds, so associate on
        // supplicant state + frequency alone; a completed 2.4 GHz association is what
        // pins the hotspot onto 2.4 GHz.
        val associatedOn2Point4 = connection.supplicantState ==
            android.net.wifi.SupplicantState.COMPLETED && connection.frequency in 2412..2484
        if (!associatedOn2Point4) return
        AppLog.i("LocalHotspot: car Wi-Fi client is associated on ${connection.frequency} MHz (2.4 GHz); disconnecting it so the hotspot can use 5 GHz")
        runCatching { wifi.disconnect() }
            .onFailure { AppLog.w("LocalHotspot: could not disconnect the Wi-Fi client (${it.javaClass.simpleName}); if the hotspot lands on 2.4 GHz, turn the car's Wi-Fi switch off") }
    }

    private fun startFiveGhzHotspot(callback: WifiManager.LocalOnlyHotspotCallback) {
        // Android 13 accepts a custom LOHS configuration. BYD's Android 12 build accepts
        // the same entry point but returns a 2.4 GHz hotspot regardless of the requested
        // band (observed 2026-09-28 with the Wi-Fi client both on and off), while its
        // plain reservation runs 5 GHz — so Android 11/12 keep the plain path and rely on
        // the band check in resolve().
        if (Build.VERSION.SDK_INT == 33 || Build.VERSION.SDK_INT >= 36) {
            try {
                val builder = SoftApConfiguration.Builder()
                SoftApConfiguration.Builder::class.java.getMethod("setSsid", String::class.java)
                    .invoke(builder, "DiAuto-${UUID.randomUUID().toString().take(6)}")
                SoftApConfiguration.Builder::class.java.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(builder, UUID.randomUUID().toString().replace("-", "").take(20), SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                @Suppress("DEPRECATION")
                val stationFrequency = runCatching { wifi.connectionInfo?.frequency }.getOrNull()
                val channel = LocalHotspotPolicy.preferredFiveGhzChannel(stationFrequency)
                try {
                    SoftApConfiguration.Builder::class.java.getMethod("setChannel", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(builder, channel, SoftApConfiguration.BAND_5GHZ)
                } catch (_: NoSuchMethodException) {
                    // Android 11 has no setChannel(channel, band); the band alone still pins 5 GHz.
                    SoftApConfiguration.Builder::class.java.getMethod("setBand", Int::class.javaPrimitiveType)
                        .invoke(builder, SoftApConfiguration.BAND_5GHZ)
                }
                val method = if (Build.VERSION.SDK_INT >= 36) "startLocalOnlyHotspotWithConfiguration" else "startLocalOnlyHotspot"
                WifiManager::class.java.getMethod(method, SoftApConfiguration::class.java, Executor::class.java,
                    WifiManager.LocalOnlyHotspotCallback::class.java)
                    .invoke(wifi, builder.build(), Executor { main.post(it) }, callback)
                AppLog.i("LocalHotspot: requested 5 GHz channel $channel reservation with normal app permission")
                return
            } catch (failure: ReflectiveOperationException) {
                if (failure.cause != null && failure.cause !is SecurityException && failure.cause !is UnsupportedOperationException) {
                    AppLog.w("LocalHotspot: 5 GHz request rejected (${failure.cause!!.javaClass.simpleName}); falling back to the standard reservation")
                }
            } catch (_: SecurityException) {
                // No privileged permission is requested to enable this optional path.
            }
        }
        wifi.startLocalOnlyHotspot(callback, main)
        AppLog.i("LocalHotspot: requested legacy reservation; waiting to verify actual AP band")
    }

    // A credential refresh never restarts the AP or resets its startup timeout.
    fun refresh() { main.post {
        if (requested) publish()
        else lastFailure?.let { AppLog.w("LocalHotspot: credentials unavailable: $it (sdk=${Build.VERSION.SDK_INT})") }
    } }
    fun stop() { main.post { stopOnMain() } }

    private fun current(token: Int) = requested && generation == token
    private fun stopOnMain() {
        requested = false
        generation++
        timeout?.let(main::removeCallbacks); timeout = null
        resolveJob?.cancel(); resolveJob = null
        val owned = reservation
        reservation = null
        ready = null
        runCatching { owned?.close() }
    }
    private fun fail(message: String) {
        lastFailure = message
        stopOnMain()
        invalidated?.invoke()
        report(message)
    }
    private fun report(message: String) {
        AppLog.w("LocalHotspot: $message")
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    private fun publish() {
        ready?.let { listener?.invoke(it.ssid, it.password, it.ip, it.bssid) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(token: Int, owned: WifiManager.LocalOnlyHotspotReservation, before: Set<String>, upstreams: Set<String>) {
        resolveJob = scope.launch(Dispatchers.IO) {
            try {
                val modern = if (Build.VERSION.SDK_INT >= 30) owned.softApConfiguration else null
                val legacy = if (modern == null) owned.wifiConfiguration else null
                val ssid = modern?.ssid ?: legacy?.SSID
                val password = modern?.passphrase ?: legacy?.preSharedKey
                // The Bluetooth handshake below advertises WPA2; do not mislabel an SAE-only AP.
                val wpa2 = if (modern != null) modern.securityType in listOf(1, 2)
                    else legacy?.allowedKeyManagement?.get(android.net.wifi.WifiConfiguration.KeyMgmt.WPA2_PSK) == true
                if (ssid.isNullOrBlank() || password.isNullOrBlank() || !wpa2) {
                    withContext(Dispatchers.Main) { if (current(token)) fail(context.getString(R.string.local_hotspot_no_wpa2)) }
                    return@launch
                }
                // Configured BSSID is often null. Only accept a newly assigned AP address;
                // the existing station, cellular interface and a foreign P2P group are excluded.
                val legacyRadio = Build.VERSION.SDK_INT != 33 && Build.VERSION.SDK_INT < 36
                val bandHint = if (modern != null) when (runCatching { modern.javaClass.getMethod("getBand").invoke(modern) as? Int }.getOrNull()) {
                    SoftApConfiguration.BAND_5GHZ -> "5 GHz"
                    SoftApConfiguration.BAND_2GHZ -> "2.4 GHz"
                    else -> null
                } else when (runCatching { legacy?.javaClass?.getField("apBand")?.getInt(legacy) }.getOrNull()) {
                    1 -> "5 GHz"
                    0 -> "2.4 GHz"
                    else -> null
                }
                AppLog.i("LocalHotspot: reservation band=${bandHint ?: "unknown"} security=wpa2")
                val settledRadio = LegacyHotspotRadio.Settled()
                var lastInterface: String? = null
                var radioStarted = 0L
                var loggedRadio: Pair<String, Int>? = null
                var unverifiedLogged: String? = null
                while (isActive) {
                    val candidate = LocalHotspotPolicy.pick(interfaces(), before, upstreams + upstreamInterfaces())
                    if (candidate == null) {
                        settledRadio.observe(null, android.os.SystemClock.elapsedRealtime())
                    }
                    if (candidate != null) {
                        if (legacyRadio) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (lastInterface != candidate.name) {
                                lastInterface = candidate.name
                                radioStarted = now
                                settledRadio.observe(null, now)
                                unverifiedLogged = null
                            }
                            val reading = LegacyHotspotRadio.read(candidate.name, bandHint)
                            val frequency = settledRadio.observe(reading.frequencyMHz, now)
                            when {
                                // A settled live reading outranks everything: trust it, 5 GHz or not.
                                frequency != null -> if (frequency !in 5160..5895) {
                                    withContext(Dispatchers.Main) { if (current(token)) fail(context.getString(R.string.local_hotspot_not_5ghz, frequency)) }
                                    return@launch
                                } else if (loggedRadio != (candidate.name to frequency)) {
                                    loggedRadio = candidate.name to frequency
                                    AppLog.i("LocalHotspot: verified legacy AP ${candidate.name} frequency=${frequency}MHz")
                                }
                                // Every BYD Qualcomm tested answers WEXT with errno 95 and Android
                                // 11/12 has no live channel callback. Where the framework's own
                                // configuration already says 5 GHz, publish without a live reading;
                                // the phone joining the AP is the real verification.
                                bandHint == "5 GHz" && now - radioStarted >= 3_000 -> if (unverifiedLogged != candidate.name) {
                                    unverifiedLogged = candidate.name
                                    AppLog.i("LocalHotspot: 5 GHz band confirmed by configuration on ${candidate.name}; channel radio unreadable (${reading.error ?: "no reading"}); publishing without live verification")
                                }
                                bandHint == "5 GHz" -> {
                                    delay(250)
                                    continue
                                }
                                bandHint == "2.4 GHz" -> {
                                    // Android 10 BYD firmware pins the local hotspot to
                                    // 2.4 GHz regardless of the Wi-Fi switch (extracted-
                                    // firmware fact). Android 11/12 can instead be dragged
                                    // down by a 2.4 GHz-associated car Wi-Fi client, where
                                    // switching it off is the remedy.
                                    if (Build.VERSION.SDK_INT < 30) {
                                        withContext(Dispatchers.Main) { if (current(token)) fail(context.getString(R.string.local_hotspot_android10_24ghz)) }
                                    } else {
                                        withContext(Dispatchers.Main) { if (current(token)) fail(context.getString(R.string.local_hotspot_client_24ghz)) }
                                    }
                                    return@launch
                                }
                                now - radioStarted >= 6_000 -> {
                                    withContext(Dispatchers.Main) { if (current(token)) fail(context.getString(R.string.local_hotspot_channel_unreadable, reading.error ?: context.getString(R.string.local_hotspot_radio_unsettled))) }
                                    return@launch
                                }
                                else -> {
                                    delay(250)
                                    continue
                                }
                            }
                        }
                        val net = NetworkInterface.getByName(candidate.name)
                        val mac = modern?.bssid?.toString()
                            ?.takeIf(NativeCredentialsPolicy::isUsableBssid)
                            ?: runCatching { net.hardwareAddress?.joinToString(":") { "%02x".format(it.toInt() and 255) } }.getOrNull()
                                ?.takeIf(NativeCredentialsPolicy::isUsableBssid)
                            ?: net.inetAddresses.toList().filterIsInstance<Inet6Address>()
                                .firstNotNullOfOrNull { LocalHotspotPolicy.eui64Mac(it.address) }
                            ?: InterfaceMacReader.fromSysfs(candidate.name)
                        if (NativeCredentialsPolicy.isUsableBssid(mac)) {
                            val credentials = Credentials(ssid, password, candidate.siteLocalIpv4!!, mac!!)
                            withContext(Dispatchers.Main) {
                                if (current(token) && reservation === owned) {
                                    timeout?.let(main::removeCallbacks); timeout = null
                                    ready = credentials
                                    AppLog.i("LocalHotspot: 5 GHz reservation ready on ${candidate.name}; no upstream internet required")
                                    publish()
                                }
                            }
                            return@launch
                        }
                    }
                    delay(250)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.w("LocalHotspot: resolve failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) { if (current(token)) fail(context.getString(R.string.local_hotspot_address_unreadable)) }
            }
        }
    }

    private fun interfaces(): List<ApInterfaceCandidate> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().map { net ->
            ApInterfaceCandidate(net.name, net.isLoopback, net.isUp,
                net.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }?.hostAddress)
        }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun upstreamInterfaces(): Set<String> = connectivity.allNetworks
        .mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.toSet()
}
