package be.technop.openrlink.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic Serial Port Profile (SPP) naar een ELM327/STN-adapter
 * zoals de vLinker FS2. SPP-UUID is de standaard well-known UUID.
 *
 * BELANGRIJK: open()/write()/read() blokkeren — nooit op de main-thread aanroepen.
 * De aanroeper (ViewModel) doet dat op Dispatchers.IO.
 */
@SuppressLint("MissingPermission") // permissie wordt in de UI afgedwongen vóór gebruik
class BluetoothSppTransport(
    private val device: BluetoothDevice
) : Transport {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val name: String get() = device.name ?: device.address
    override val isOpen: Boolean get() = socket?.isConnected == true

    override fun open() {
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect() // blokkeert
            input = s.inputStream
            output = s.outputStream
            socket = s
        } catch (e: Exception) {
            // Fallback: sommige goedkope adapters falen op de service-record; probeer reflectie-kanaal 1.
            try {
                val fallback = device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
                fallback.connect()
                input = fallback.inputStream
                output = fallback.outputStream
                socket = fallback
            } catch (e2: Exception) {
                throw TransportIoException("Kon Bluetooth-socket niet openen: ${e2.message}", e2)
            }
        }
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: throw TransportIoException("Transport niet open")
        try {
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            throw TransportIoException("Schrijffout: ${e.message}", e)
        }
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val ins = input ?: throw TransportIoException("Transport niet open")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            if (ins.available() > 0) {
                val n = ins.read(buf)
                if (n > 0) {
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '>') {
                            return sb.toString().trim()
                        }
                        sb.append(c)
                    }
                }
            } else {
                Thread.sleep(5)
            }
        }
        throw TransportTimeoutException("Timeout — partiële respons: '${sb.toString().trim()}'")
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }
}
