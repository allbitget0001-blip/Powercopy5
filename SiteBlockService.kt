package com.powerlock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * DNS-only site blocking, active only while a lock runs.
 *
 * The VPN carries DNS queries ONLY: the tunnel's DNS server is 10.0.0.1 and
 * the only route is to that address, so all other traffic bypasses the VPN
 * entirely. A query for a blocked host (or any of its subdomains) is answered
 * NXDOMAIN; anything else is forwarded to 8.8.8.8 and the answer relayed back.
 * No HTTPS traffic is ever seen or touched, and nothing leaves the device
 * except the forwarded DNS queries themselves.
 */
object SiteBlock {
    private val STARTERS = listOf(
        "facebook.com", "instagram.com", "youtube.com", "tiktok.com",
        "twitter.com", "x.com", "reddit.com", "netflix.com"
    )

    /** Master switch: when on, the VPN starts with every lock and stops when it ends. */
    fun isOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("site_on", false)

    fun setOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("site_on", on).apply()
    }

    fun blocklist(ctx: Context): List<String> {
        val p = Store.settings(ctx)
        if (!p.contains("site_list")) {
            saveBlocklist(ctx, STARTERS)
            return STARTERS
        }
        return (p.getString("site_list", "") ?: "").split(",").filter { it.isNotEmpty() }
    }

    fun saveBlocklist(ctx: Context, list: List<String>) {
        Store.settings(ctx).edit().putString("site_list", list.joinToString(",")).apply()
    }

    /** Normalizes user input ("https://www.Facebook.com/feed" -> "facebook.com"), or null when invalid. */
    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.startsWith("http://")) s = s.removePrefix("http://")
        if (s.startsWith("https://")) s = s.removePrefix("https://")
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        if (s.isEmpty() || !s.contains('.') || s.contains(' ') || s.contains(':')) return null
        if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        if (s.startsWith('.') || s.endsWith('.') || s.contains("..")) return null
        return s
    }

    /** True when [host] is blocked: an exact match or any parent domain on the list. */
    fun isBlocked(ctx: Context, host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        for (d in blocklist(ctx)) {
            if (h == d || h.endsWith(".$d")) return true
        }
        return false
    }

    /** VPN consent already granted? VpnService.prepare returns null when yes. */
    fun hasPermission(ctx: Context): Boolean = VpnService.prepare(ctx) == null

    /** Start/stop the VPN to match the desired state: on only while a lock runs. */
    fun sync(ctx: Context) {
        var want = false
        try {
            want = isOn(ctx) && Store.isActive(ctx) && hasPermission(ctx)
            val action = if (want) SiteBlockService.ACTION_START else SiteBlockService.ACTION_STOP
            val i = Intent(ctx, SiteBlockService::class.java).setAction(action)
            // startForegroundService: the service goes foreground at once, so
            // this is safe even from an alarm receiver or the watchdog worker
            ctx.startForegroundService(i)
        } catch (e: Exception) {
            Log.e(TAG, "SiteBlock sync failed", e)
            // Android 12+ refuses a foreground-service start from the background
            // (inexact-alarm lock starts have no exemption): ask for an
            // alarm-clock restart instead — when KeepAlive starts it re-runs
            // sync from a foreground context, so the VPN is back in ~1 s
            // instead of up to 15 min. Only for START: when the VPN should be
            // off there is nothing to start, and a failed STOP is harmless.
            // The exception type is matched by name (it only exists on API 31+).
            if (want && e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                try {
                    val p = Store.settings(ctx)
                    val now = System.currentTimeMillis()
                    if (now - p.getLong("site_retry_at", 0) > 60_000L) {
                        p.edit().putLong("site_retry_at", now).apply()
                        KeepAliveService.restartSoon(ctx, 1000)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}

class SiteBlockService : VpnService() {

    companion object {
        const val ACTION_START = "com.powerlock.app.SITEBLOCK_START"
        const val ACTION_STOP = "com.powerlock.app.SITEBLOCK_STOP"
        private const val VPN_ADDR = "10.0.0.2"
        private const val DNS_ADDR = "10.0.0.1"
        private const val UPSTREAM = "8.8.8.8"
        private const val UPSTREAM_PORT = 53
    }

    @Volatile
    private var tun: ParcelFileDescriptor? = null
    @Volatile
    private var running = false
    private var worker: Thread? = null
    // DNS forwarding runs on a small pool: one slow upstream query must not
    // stall every other lookup (forwarding used to run on the single reader
    // thread with a 2.5 s timeout).
    private var dnsPool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diag.init(this) // backstop: App.onCreate already installed the handler
        Diag.crumb("SiteBlockService.onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> startVpn()
            // START_STICKY restart delivers a null intent: only restart the
            // VPN when a lock is actually running, otherwise stop
            else -> if (SiteBlock.isOn(this) && Store.isActive(this) && SiteBlock.hasPermission(this)) startVpn() else stopSelf()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("powerlock_vpn", "Site block", NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val nb = Notification.Builder(this, "powerlock_vpn")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(L.t("Site block is on", "সাইট ব্লক চালু"))
                .setContentText(L.t("Blocked sites cannot open while the lock runs.", "লক চলাকালীন ব্লক করা সাইট খুলবে না।"))
                .setContentIntent(pi)
            startForeground(3, nb.build())
            val fd = Builder()
                .addAddress(VPN_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32) // only DNS queries enter the tunnel
                .setSession("PowerLock 2")
                .establish()
            if (fd == null) { // another VPN is active, or consent was revoked
                Log.w(TAG, "SiteBlock establish returned null")
                stopVpn()
                return
            }
            tun = fd
            dnsPool = Executors.newFixedThreadPool(4) { r ->
                Thread(r, "siteblock-fwd").apply { isDaemon = true }
            }
            running = true
            worker = thread(name = "siteblock-dns", isDaemon = true) { loop() }
        } catch (e: Exception) {
            Log.e(TAG, "SiteBlock start failed", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        running = false
        try {
            dnsPool?.shutdownNow()
        } catch (_: Exception) {
        }
        dnsPool = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        worker?.interrupt()
        worker = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        running = false
        try {
            dnsPool?.shutdownNow()
        } catch (_: Exception) {
        }
        dnsPool = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** Reads IP packets from the tunnel; answers DNS, ignores everything else.
     * Each packet is handled on the pool: a slow upstream query stalls only
     * its own thread, never the reader or the other queries. */
    private fun loop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val packet = ByteArray(32767)
        while (running) {
            try {
                val len = input.read(packet)
                if (len <= 0) continue
                val copy = packet.copyOf(len) // the pool must not see the reused buffer
                val pool = dnsPool
                if (pool == null || pool.isShutdown) continue
                pool.execute {
                    try {
                        val response = handlePacket(copy, copy.size) ?: return@execute
                        synchronized(output) { output.write(response) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "SiteBlock dns error", e)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "SiteBlock loop error", e)
                break
            }
        }
    }

    /**
     * Returns the full IPv4 response packet for a DNS query, or null when the
     * packet is not an IPv4 UDP DNS query (or the answer could not be built).
     */
    private fun handlePacket(packet: ByteArray, len: Int): ByteArray? {
        try {
            if (len < 28) return null
            if ((packet[0].toInt() shr 4) != 4) return null // IPv4 only
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || len < ihl + 8) return null
            if (packet[9].toInt() != 17) return null // UDP only
            val srcIp = packet.copyOfRange(12, 16)
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) return null
            val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
            val dnsOff = ihl + 8
            val dnsLen = minOf(udpLen - 8, len - dnsOff)
            if (dnsLen < 12) return null
            val dns = packet.copyOfRange(dnsOff, dnsOff + dnsLen)
            val host = parseQuestionHost(dns) ?: return null
            val payload = if (SiteBlock.isBlocked(this, host)) {
                buildDnsError(dns, 3) // NXDOMAIN
            } else {
                forwardDns(dns) ?: buildDnsError(dns, 2) // upstream failed: SERVFAIL
            }
            if (payload.isEmpty()) return null
            return wrapIpUdp(srcIp, srcPort, ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF), payload)
        } catch (e: Exception) {
            Log.e(TAG, "SiteBlock packet failed", e)
            return null
        }
    }

    /** The queried hostname from a DNS question section, or null when malformed. */
    private fun parseQuestionHost(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val qdCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
        if (qdCount != 1) return null
        val labels = ArrayList<String>()
        var off = 12
        while (off < dns.size) {
            val l = dns[off].toInt() and 0xFF
            if (l == 0) break
            if (l and 0xC0 != 0) return null // compression pointers never appear in questions
            off++
            if (off + l > dns.size) return null
            labels.add(String(dns, off, l, Charsets.US_ASCII))
            off += l
            if (labels.size > 20) return null
        }
        if (labels.isEmpty()) return null
        return labels.joinToString(".")
    }

    /** Length of the question section (QNAME + QTYPE + QCLASS), or -1 when malformed. */
    private fun questionLength(dns: ByteArray): Int {
        var off = 12
        while (off < dns.size) {
            val l = dns[off].toInt() and 0xFF
            if (l == 0) {
                off++
                break
            }
            if (l and 0xC0 != 0) return -1
            off += 1 + l
            if (off > dns.size) return -1
        }
        return if (off + 4 <= dns.size) (off - 12) + 4 else -1
    }

    /** A DNS error response reusing the query's ID and question: RCODE 3 = NXDOMAIN, 2 = SERVFAIL. */
    private fun buildDnsError(dns: ByteArray, rcode: Int): ByteArray {
        val qLen = questionLength(dns)
        if (qLen < 0) return ByteArray(0)
        val out = ByteArray(12 + qLen)
        out[0] = dns[0]
        out[1] = dns[1]
        out[2] = 0x81.toByte()
        out[3] = (0x80 or rcode).toByte() // QR=1, RD=1, RA=1, RCODE
        out[4] = 0
        out[5] = 1 // QDCOUNT = 1
        // ANCOUNT/NSCOUNT/ARCOUNT stay 0
        System.arraycopy(dns, 12, out, 12, qLen)
        return out
    }

    /** Forwards one DNS query to the upstream resolver and returns its answer
     * (or null on timeout). Each query gets its own socket: with several pool
     * threads forwarding at once, a shared socket could hand a reply to the
     * wrong thread. The 65535-byte buffer fits the largest possible UDP DNS
     * reply, so no answer is ever silently truncated (TCP is not carried
     * by the tunnel). */
    private fun forwardDns(dns: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 2500
                val addr = InetAddress.getByName(UPSTREAM)
                sock.send(DatagramPacket(dns, dns.size, addr, UPSTREAM_PORT))
                val buf = ByteArray(65535)
                val reply = DatagramPacket(buf, buf.size)
                sock.receive(reply)
                buf.copyOfRange(0, reply.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Wraps a DNS payload in an IPv4 + UDP packet addressed back to the querier. */
    private fun wrapIpUdp(dstIp: ByteArray, dstPort: Int, ipId: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        // IPv4 header
        out[0] = 0x45
        out[1] = 0
        out[2] = (total shr 8).toByte()
        out[3] = total.toByte()
        out[4] = (ipId shr 8).toByte()
        out[5] = ipId.toByte()
        out[6] = 0x40 // don't fragment
        out[7] = 0
        out[8] = 64 // TTL
        out[9] = 17 // UDP
        // checksum bytes 10-11 filled in after the header is complete
        out[12] = 10
        out[13] = 0
        out[14] = 0
        out[15] = 1 // from 10.0.0.1
        System.arraycopy(dstIp, 0, out, 16, 4)
        val sum = ipChecksum(out, 0, 20)
        out[10] = (sum shr 8).toByte()
        out[11] = sum.toByte()
        // UDP header (checksum 0 is allowed for IPv4)
        out[20] = 0
        out[21] = 53
        out[22] = (dstPort shr 8).toByte()
        out[23] = dstPort.toByte()
        val udpLen = 8 + payload.size
        out[24] = (udpLen shr 8).toByte()
        out[25] = udpLen.toByte()
        out[26] = 0
        out[27] = 0
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    /** One's-complement checksum over [len] header bytes starting at [off]. */
    private fun ipChecksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i + 1 < off + len) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < off + len) sum += (buf[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
