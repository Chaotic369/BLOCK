package com.example.appblocker.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.appblocker.data.model.BlockedDomain
import com.example.appblocker.di.AppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Local DNS sinkhole. Only DNS traffic is routed through the tunnel; queries for
 * blocked domains get NXDOMAIN, everything else is forwarded to an upstream resolver.
 */
class BlockDnsVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Job? = null
    private var domainsJob: Job? = null
    @Volatile private var blocked: List<BlockedDomain> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        // Replace the previous collector instead of stacking a new one on every start.
        domainsJob?.cancel()
        domainsJob = scope.launch { AppModule.blockRepository.domains.collect { blocked = it } }

        if (worker?.isActive != true) {
            val fd = runCatching {
                Builder()
                    .setSession("App Blocker DNS")
                    .addAddress(CLIENT_ADDRESS, 32)
                    .addDnsServer(VIRTUAL_DNS)
                    .addRoute(VIRTUAL_DNS, 32)
                    // The tunnel is non-blocking by default, which makes read() return 0
                    // immediately and spins a CPU core. Blocking mode parks the thread instead.
                    .setBlocking(true)
                    .establish()
            }.getOrNull()
            if (fd == null) {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            tunnel = fd
            isRunning = true
            worker = scope.launch { runLoop(fd) }
        }
        return START_STICKY
    }

    private fun runLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        val upstream = InetAddress.getByName(UPSTREAM_DNS)
        while (scope.isActive()) {
            val length = try { input.read(buffer) } catch (e: Exception) { break }
            if (length < 0) break
            if (length == 0) continue
            val packet = buffer.copyOf(length)
            scope.launch {
                handlePacket(packet, upstream)?.let {
                    synchronized(output) { runCatching { output.write(it) } }
                }
            }
        }
    }

    private fun CoroutineScope.isActive(): Boolean = coroutineContext[Job]?.isActive == true

    private fun handlePacket(packet: ByteArray, upstream: InetAddress): ByteArray? {
        if (packet.size < 28 || (packet[0].toInt() shr 4) != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet[9].toInt() != 17) return null
        val dstPort = u16(packet, ihl + 2)
        if (dstPort != 53) return null
        val dnsStart = ihl + 8
        val dns = packet.copyOfRange(dnsStart, packet.size)
        val name = parseQName(dns) ?: return null
        val now = System.currentTimeMillis()
        val isBlocked = blocked.any { it.isEnforcing(now) && DomainMatcher.matches(name, it.domain) }

        val response = if (isBlocked) {
            nxdomain(dns)
        } else {
            forward(dns, upstream) ?: return null
        }
        return buildReply(packet, ihl, response)
    }

    private fun forward(query: ByteArray, upstream: InetAddress): ByteArray? = try {
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = 4_000
            socket.send(DatagramPacket(query, query.size, upstream, 53))
            val buf = ByteArray(4096)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            buf.copyOf(reply.length)
        }
    } catch (e: Exception) {
        null
    }

    private fun nxdomain(query: ByteArray): ByteArray {
        // Keep header + question only, set QR, RD, RA and RCODE=3 (NXDOMAIN).
        var end = 12
        while (end < query.size && query[end].toInt() != 0) end += (query[end].toInt() and 0xFF) + 1
        end += 5 // null label + QTYPE + QCLASS
        val out = query.copyOf(minOf(end, query.size))
        out[2] = 0x81.toByte()
        out[3] = 0x83.toByte()
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        return out
    }

    private fun parseQName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        val parts = mutableListOf<String>()
        var i = 12
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 1 + len > dns.size) return null
            parts += String(dns, i + 1, len, Charsets.US_ASCII)
            i += len + 1
        }
        return parts.joinToString(".").lowercase().ifEmpty { null }
    }

    private fun buildReply(request: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = 20 + 8 + dns.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64
        out[9] = 17
        System.arraycopy(request, 16, out, 12, 4) // src = original dst
        System.arraycopy(request, 12, out, 16, 4) // dst = original src
        val checksum = ipChecksum(out, 20)
        out[10] = (checksum shr 8).toByte(); out[11] = checksum.toByte()
        // UDP: swap ports
        System.arraycopy(request, ihl + 2, out, 20, 2)
        System.arraycopy(request, ihl, out, 22, 2)
        val udpLen = 8 + dns.size
        out[24] = (udpLen shr 8).toByte(); out[25] = udpLen.toByte()
        // UDP checksum left as 0 (optional for IPv4)
        System.arraycopy(dns, 0, out, 28, dns.size)
        return out
    }

    private fun ipChecksum(data: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun shutdown() {
        isRunning = false
        domainsJob?.cancel()
        domainsJob = null
        worker?.cancel()
        worker = null
        runCatching { tunnel?.close() }
        tunnel = null
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.example.appblocker.action.STOP_VPN"
        private const val CLIENT_ADDRESS = "10.7.0.2"
        private const val VIRTUAL_DNS = "10.7.0.1"
        private const val UPSTREAM_DNS = "8.8.8.8"

        /** True while the tunnel is up. Used by the watchdog to restart it if it dies. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Returns an intent for the system consent dialog, or null if already granted. */
        fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

        /** Starts the filter if the user already approved the VPN. Never throws. */
        fun start(context: Context) {
            if (VpnService.prepare(context) != null) return
            runCatching { context.startService(Intent(context, BlockDnsVpnService::class.java)) }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, BlockDnsVpnService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
