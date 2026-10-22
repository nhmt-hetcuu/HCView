package com.hetcuu.hcview.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    data class NetworkIpInfo(
        val ipAddress: String,
        val interfaceName: String,
        val isHotspot: Boolean
    )

    /**
     * Lấy danh sách tất cả địa chỉ IPv4 không phải loopback của điện thoại.
     */
    fun getLocalIpv4Addresses(): List<NetworkIpInfo> {
        val result = mutableListOf<NetworkIpInfo>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val name = intf.name.lowercase()
                        val isHotspot = name.contains("ap") || name.contains("swlan") || name.contains("rndis")
                        result.add(NetworkIpInfo(host, intf.displayName ?: intf.name, isHotspot))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi lấy danh sách IP", e)
        }
        return result
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    fun acquireLocks(context: Context, keepScreenOn: Boolean = true) {
        try {
            if (wifiLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "HCView:WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            if (wakeLock == null) {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HCView:WakeLock").apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L) // 12 hours max
                }
            }

            if (keepScreenOn && screenWakeLock == null) {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "HCView:ScreenWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
                Log.i(TAG, "Screen wake lock acquired: screen will stay on")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi acquire locks", e)
        }
    }

    fun releaseLocks() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null

            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null

            screenWakeLock?.let {
                if (it.isHeld) it.release()
            }
            screenWakeLock = null
            Log.i(TAG, "Locks released")
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi release locks", e)
        }
    }
}
