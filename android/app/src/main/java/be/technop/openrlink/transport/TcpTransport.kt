package be.technop.openrlink.transport

import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP-transport naar de M5StickC Plus2 WiFi↔CAN-brug (firmware: M5CanBridge).
 * De Stick maakt een AP "TwizyBridge" (default IP 192.168.4.1, poort 35000)
 * en gedraagt zich als een WiFi-ELM327 — zelfde commandoset, dus de rest
 * van de app werkt ongewijzigd.
 */
class TcpTransport(
    private val host: String = "192.168.4.1",
    private val port: Int = 35000
) : Transport {

    private var socket: Socket? = null
    override val name get() = "M5 Bridge $host:$port"
    override val isOpen get() = socket?.isConnected == true && socket?.isClosed == false

    override fun open() {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 100
            socket = s
        } catch (e: Exception) {
            throw TransportIoException("TCP-verbinding mislukt ($host:$port) — telefoon op WiFi 'TwizyBridge'?", e)
        }
    }

    override fun write(bytes: ByteArray) {
        val s = socket ?: throw TransportIoException("TCP niet open")
        try { s.getOutputStream().apply { write(bytes); flush() } }
        catch (e: Exception) { throw TransportIoException("TCP schrijffout: ${e.message}", e) }
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val s = socket ?: throw TransportIoException("TCP niet open")
        val ins = s.getInputStream()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            val n = try { ins.read(buf) } catch (_: java.net.SocketTimeoutException) { 0 }
            if (n < 0) throw TransportIoException("TCP-verbinding gesloten door brug")
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == '>') return sb.toString().trim()
                sb.append(c)
            }
        }
        throw TransportTimeoutException("TCP timeout — partieel: '${sb.toString().trim()}'")
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
