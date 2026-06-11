package be.technop.openrlink.transport

/**
 * Abstractie over de fysieke link naar de adapter.
 * Implementaties: BluetoothSppTransport (vLinker FS2), DemoTransport (geen hardware nodig).
 * USB-OTG kan later als derde implementatie zonder de rest te raken.
 */
interface Transport {
    val name: String
    val isOpen: Boolean

    /** Opent de verbinding. Blokkeert; roep aan op een achtergrond-thread/dispatcher. */
    fun open()

    /** Stuurt ruwe bytes (commando + CR) naar de adapter. */
    fun write(bytes: ByteArray)

    /**
     * Leest tot de ELM-prompt '>' (0x3E) of timeout. Geeft de raw respons terug
     * zónder de prompt. Throwt TransportTimeoutException bij timeout.
     */
    fun readUntilPrompt(timeoutMs: Long = 4000): String

    fun close()
}

class TransportTimeoutException(msg: String) : Exception(msg)
class TransportIoException(msg: String, cause: Throwable? = null) : Exception(msg, cause)
