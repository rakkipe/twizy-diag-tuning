package be.technop.openrlink.elm

/** ELM text to complete ISO-TP PDUs. Never joins different ECU responses. */
object ElmResponse {
    data class Message(val source: String?, val data: List<Int>)
    private data class Pending(val size: Int, val data: MutableList<Int>, var sequence: Int = 1)

    fun messages(raw: String, expectedSource: String? = null): List<Message> {
        if (Regex("NO DATA|ERROR|BUFFER FULL|STOPPED|UNABLE", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return emptyList()
        val out = mutableListOf<Message>()
        val pending = mutableMapOf<String?, Pending>()
        val indexed = mutableListOf<Int>()
        var indexedSize: Int? = null
        var nextIndex = 0
        var indexedInvalid = false
        for (line in raw.replace('>', ' ').uppercase().split(Regex("[\\r\\n]+"))) {
            var s = line.trim()
            val index = Regex("^([0-9A-F]+):\\s*(.*)$").matchEntire(s)
            if (index != null) {
                if (index.groupValues[1].toInt(16) != nextIndex++) indexedInvalid = true
                val hex = index.groupValues[2].replace(" ", "")
                if (!hex.matches(Regex("(?:[0-9A-F]{2})+"))) { indexedInvalid = true; continue }
                indexed.addAll(hex.chunked(2).map { it.toInt(16) })
                continue
            }
            // Headerless CAF1 long responses start with a three-digit byte count.
            if (s.matches(Regex("[0-9A-F]{3}"))) { indexedSize = s.toInt(16); continue }
            var source: String? = null
            val header = Regex("^([0-9A-F]{3}|[0-9A-F]{8})\\s+(.+)$").matchEntire(s)
            if (header != null) { source = header.groupValues[1]; s = header.groupValues[2] }
            else {
                val compact = s.replace(" ", "")
                if (compact.matches(Regex("[0-9A-F]+")) && compact.length % 2 == 1 && compact.length > 5) {
                    source = compact.take(3); s = compact.drop(3)
                } else if (expectedSource?.length == 8 && compact.startsWith(expectedSource.uppercase())) {
                    source = compact.take(8); s = compact.drop(8)
                }
            }
            if (source != null && expectedSource != null && source != expectedSource.uppercase()) continue
            // Optional one-nibble DLC printed by ATD1.
            s = s.replace(Regex("^[0-8]\\s+"), "").replace(" ", "")
            if (!s.matches(Regex("(?:[0-9A-F]{2})+"))) continue
            val b = s.chunked(2).map { it.toInt(16) }
            when (b[0] shr 4) {
                0 -> {
                    val n = b[0] and 15
                    if (n in 1..7 && b.size >= n + 1) out.add(Message(source, b.drop(1).take(n)))
                }
                1 -> {
                    if (b.size < 3) continue
                    val n = ((b[0] and 15) shl 8) or b[1]
                    if (n > 7) pending[source] = Pending(n, b.drop(2).toMutableList())
                }
                2 -> {
                    val p = pending[source] ?: continue
                    if (b[0] and 15 != p.sequence) { pending.remove(source); continue }
                    p.sequence = (p.sequence + 1) and 15
                    p.data.addAll(b.drop(1))
                    if (p.data.size >= p.size) { out.add(Message(source, p.data.take(p.size))); pending.remove(source) }
                }
                3 -> Unit // flow control is not diagnostic data
                else -> if (source == null && b[0] in 0x40..0x7F) out.add(Message(null, b))
            }
        }
        if (indexed.isNotEmpty() && !indexedInvalid && indexedSize != null && indexed.size >= indexedSize)
            out.add(Message(null, indexed.take(indexedSize)))
        return out
    }
}
