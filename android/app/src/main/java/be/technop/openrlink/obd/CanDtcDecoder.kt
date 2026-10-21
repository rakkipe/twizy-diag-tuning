package be.technop.openrlink.obd

import be.technop.openrlink.elm.ElmResponse

object CanDtcDecoder {
    /** CAN OBD includes a count byte. Missing/negative/malformed replies are never a clean scan. */
    fun decode(raw: String, service: Int = 0x43): List<String> {
        val messages = ElmResponse.messages(raw).filter { it.data.firstOrNull() == service }
        require(messages.isNotEmpty()) { "OBD-dienst %02X: geen geldig antwoord".format(service - 0x40) }
        return messages.flatMap { m ->
            val b = m.data
            require(b.size >= 2 && b.size >= 2 + b[1] * 2) { "Onvolledige OBD-foutcodelijst" }
            (0 until b[1]).mapNotNull { i ->
                val a = b[2 + i * 2]; val c = b[3 + i * 2]
                if (a == 0 && c == 0) null
                else "%c%d%X%02X".format("PCBU"[(a shr 6) and 3], (a shr 4) and 3, a and 15, c)
            }
        }.distinct()
    }
}
