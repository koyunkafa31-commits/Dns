package com.tecno.netfix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.*
import android.util.Log
import android.widget.*
import java.io.*
import java.net.*

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40)
        }
        logView = TextView(this).apply { textSize = 12f; text = "TECNO NET FIX\n──────────────\n" }

        lay.addView(Button(this).apply {
            text = "🔧 TEŞHİS + ONAR"
            setOnClickListener { Thread { repair() }.start() }
        })
        lay.addView(Button(this).apply {
            text = "🌐 DNS VPN (ROOT GEREKMEZ)"
            setOnClickListener { toggleVpn() }
        })
        lay.addView(Button(this).apply {
            text = "⚡ ROOT ONAR (RADYO)"
            setOnClickListener { Thread { rootRepair() }.start() }
        })
        lay.addView(logView)
        setContentView(ScrollView(this).apply { addView(lay) })

        log("Cihaz: ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        log("Root: ${if (hasRoot()) "VAR ✅" else "YOK ❌"}")
    }

    private fun log(m: String) = h.post { logView.append("\n$m") }

    private fun hasRoot(): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
    } catch (e: Exception) { false }

    private fun sh(c: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", c))
        p.waitFor()
        BufferedReader(InputStreamReader(p.inputStream)).readText().trim()
    } catch (e: Exception) { "" }

    private fun repair() {
        log("── TEŞHİS ──")
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        log("Aktif ağ: ${if (n != null) "VAR" else "YOK"}")
        log("Wi-Fi: ${cm.getNetworkCapabilities(n)?.hasTransport(1) ?: false}")
        log("Mobil: ${cm.getNetworkCapabilities(n)?.hasTransport(0) ?: false}")

        val ping = ping("8.8.8.8")
        val dns  = dnsResolve("google.com")
        log("Ping: ${if (ping) "OK ✅" else "FAIL ❌"}")
        log("DNS : ${if (dns)  "OK ✅" else "FAIL ❌"}")

        when {
            !ping -> log("\n⚠️ RADYO ölü → ROOT ONAR butonu")
            ping && !dns -> log("\n⚠️ DNS bozuk → DNS VPN butonu (KİLİT ÇÖZÜM)")
            else -> log("\n✅ Ağ sağlam")
        }
    }

    private fun ping(h: String) = try { InetAddress.getByName(h).isReachable(3000) } catch (e: Exception) { false }
    private fun dnsResolve(h: String) = try { InetAddress.getAllByName(h).isNotEmpty() } catch (e: Exception) { false }

    private fun rootRepair() {
        if (!hasRoot()) { log("ROOT YOK"); return }
        log("── ROOT ONARIM ──")
        log("mobil kapat: ${sh("svc data disable")}"); Thread.sleep(2000)
        log("mobil aç  : ${sh("svc data enable")}");  Thread.sleep(2000)
        log("wifi reset: ${sh("svc wifi disable")}");  Thread.sleep(1000)
        log("wifi aç   : ${sh("svc wifi enable")}")
        sh("settings put global airplane_mode_on 0")
        sh("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
        log("radyo yenile: ${sh("pkill -f rild; start ril-daemon")}")
        log("✅ Bitti → TEŞHİS'e basıp kontrol et")
    }

    private fun toggleVpn() {
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1)
        else startService(Intent(this, DnsProxyVpn::class.java))
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (c == RESULT_OK) startService(Intent(this, DnsProxyVpn::class.java))
    }
}

// ═══════════════════════════════════════════════════════════
// DNS PROXY VPN — root olmadan DNS bypass (1.1.1.1 forward)
// ═══════════════════════════════════════════════════════════
class DnsProxyVpn : VpnService() {
    private var fd: ParcelFileDescriptor? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        try {
            fd = Builder().apply {
                addAddress("10.0.0.2", 32)
                addDnsServer("10.0.0.2")
                addRoute("10.0.0.2", 32)
                setSession("TecnoNetFix")
            }.establish()
            Log.i("VPN", "Tunnel açık")

            Thread {
                val tun = FileInputStream(fd!!.fileDescriptor)
                val out = FileOutputStream(fd!!.fileDescriptor)
                val buf = ByteArray(4096)
                while (true) {
                    val len = tun.read(buf)
                    if (len < 28) continue
                    val ihl = (buf[0].toInt() and 0x0F) * 4
                    val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
                    if (dstPort != 53) continue

                    val dnsOff = ihl + 8
                    val dnsLen = len - dnsOff
                    val q = buf.copyOfRange(dnsOff, dnsOff + dnsLen)

                    try {
                        val sk = DatagramSocket().apply { soTimeout = 3000 }
                        sk.send(DatagramPacket(q, dnsLen, InetAddress.getByName("1.1.1.1"), 53))
                        val rb = ByteArray(4096)
                        val rp = DatagramPacket(rb, rb.size)
                        sk.receive(rp); sk.close()

                        val resp = rb.copyOfRange(0, rp.length)
                        val pkt = ByteArray(ihl + 8 + resp.size)
                        System.arraycopy(buf, 0, pkt, 0, ihl)
                        // IP'leri yer değiştir
                        System.arraycopy(buf, 16, pkt, 12, 4)
                        System.arraycopy(buf, 12, pkt, 16, 4)
                        // UDP header
                        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl+1].toInt() and 0xFF)
                        pkt[ihl]   = (dstPort ushr 8).toByte(); pkt[ihl+1] = dstPort.toByte()
                        pkt[ihl+2] = (srcPort ushr 8).toByte(); pkt[ihl+3] = srcPort.toByte()
                        val ul = 8 + resp.size
                        pkt[ihl+4] = (ul ushr 8).toByte(); pkt[ihl+5] = ul.toByte()
                        System.arraycopy(resp, 0, pkt, ihl + 8, resp.size)
                        // IP checksum
                        pkt[10] = 0; pkt[11] = 0
                        var sum = 0
                        for (k in 0 until 20 step 2)
                            sum += ((pkt[k].toInt() and 0xFF) shl 8) or (pkt[k+1].toInt() and 0xFF)
                        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                        sum = sum.inv() and 0xFFFF
                        pkt[10] = (sum ushr 8).toByte(); pkt[11] = sum.toByte()
                        out.write(pkt)
                    } catch (e: Exception) { Log.e("VPN", "dns", e) }
                }
            }.start()
        } catch (e: Exception) { Log.e("VPN", "start", e) }
        return START_STICKY
    }

    override fun onDestroy() { fd?.close(); super.onDestroy() }
}