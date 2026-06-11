package be.technop.openrlink.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * USB-OTG transport voor de vLinker FS (USB-variant, FTDI-chip, STN1170).
 * Baudrate 115200 — zelfde als de DDT4ALL-setup op COM8.
 *
 * Vereist: OTG-kabel/adapter (USB-C → USB-A female) tussen telefoon en vLinker.
 * Bij eerste gebruik vraagt Android USB-permissie; daarna direct verbinden.
 */
class UsbSerialTransport(private val context: Context) : Transport {

    companion object {
        const val BAUD = 115200
        const val ACTION_USB_PERMISSION = "be.technop.openrlink.USB_PERMISSION"
    }

    private var port: UsbSerialPort? = null
    override val name get() = "USB ${port?.device?.productName ?: "(vLinker FS)"}"
    override val isOpen get() = port?.isOpen == true

    /** Geeft true als er een ondersteunde USB-serial adapter hangt. */
    fun deviceAvailable(): Boolean {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(mgr).isNotEmpty()
    }

    /** Vraagt USB-permissie aan indien nodig. Geeft true als al toegestaan. */
    fun ensurePermission(): Boolean {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(mgr).firstOrNull()
            ?: return false
        if (mgr.hasPermission(driver.device)) return true
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        mgr.requestPermission(driver.device, pi)
        return false
    }

    override fun open() {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(mgr).firstOrNull()
            ?: throw TransportIoException("Geen USB-serial adapter gevonden — OTG-kabel + vLinker FS aangesloten?")
        if (!mgr.hasPermission(driver.device))
            throw TransportIoException("Geen USB-permissie — druk eerst op 'USB-permissie' en sta toe.")
        val conn = mgr.openDevice(driver.device)
            ?: throw TransportIoException("Kon USB-device niet openen")
        val p = driver.ports[0]
        p.open(conn)
        p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // DTR/RTS aan — sommige FTDI/STN-adapters hebben dit nodig om te praten.
        try { p.dtr = true; p.rts = true } catch (_: Exception) {}
        port = p
    }

    override fun write(bytes: ByteArray) {
        val p = port ?: throw TransportIoException("USB niet open")
        try { p.write(bytes, 1000) }
        catch (e: Exception) { throw TransportIoException("USB schrijffout: ${e.message}", e) }
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val p = port ?: throw TransportIoException("USB niet open")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            val n = try { p.read(buf, 50) } catch (e: Exception) {
                throw TransportIoException("USB leesfout: ${e.message}", e)
            }
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == '>') return sb.toString().trim()
                sb.append(c)
            }
        }
        throw TransportTimeoutException("USB timeout — partieel: '${sb.toString().trim()}'")
    }

    override fun close() {
        try { port?.close() } catch (_: Exception) {}
        port = null
    }
}
