package be.technop.openrlink.ev

import be.technop.openrlink.elm.Elm327
import be.technop.openrlink.uds.UdsClient
import be.technop.openrlink.transport.TransportTimeoutException

data class EvSnapshot(
    val vehicle: EvVehicle,
    val timestamp: Long,
    val metrics: List<EvMetric>,
    val cells: Map<Int, Double>,
    val details: List<String>
) {
    val cellDeltaMv: Double? get() = if (cells.size == 96) (cells.values.max() - cells.values.min()) * 1000 else null
    fun report(): String = buildString {
        appendLine("OpenRLink — ${vehicle.label}")
        appendLine(java.util.Date(timestamp).toString())
        appendLine("Momentopname; softwareprofiel, nog niet fysiek gevalideerd.")
        metrics.forEach { appendLine("${it.label}: ${it.value} ${it.unit}") }
        appendLine("Celspanningen: ${cells.size}/96")
        cellDeltaMv?.let { appendLine("Celverschil: $it mV") }
        cells.toSortedMap().forEach { (i, v) -> appendLine("Cel $i: $v V") }
        appendLine(); details.forEach { appendLine(it) }
    }
}

class EvReader(private val elm: Elm327) {
    fun read(v: EvVehicle, includeCells: Boolean, checkActive: () -> Unit = {}, progress: (String) -> Unit = {}): EvSnapshot {
        require(!v.isTwizy)
        val metrics = mutableListOf<EvMetric>()
        val cells = linkedMapOf<Int, Double>()
        val details = mutableListOf<String>()
        var target: EcuTarget? = null
        val requests = EvRequests.summary(v) + if (includeCells) EvRequests.cells(v) else emptyList()
        var missedCells = 0
        for ((index, request) in requests.withIndex()) {
            checkActive()
            progress("${index + 1}/${requests.size}: ${request.target.name} ${request.command}")
            if (request.target != target) {
                elm.configUdsTarget(request.target.tx, request.target.rx)
                target = request.target
            }
            val uds = UdsClient(elm, request.target.rx)
            val result = try {
                if (request.command.startsWith("22")) uds.readDataByIdentifier(request.command.drop(2).toInt(16))
                else uds.readLocalIdentifier(request.command.drop(2).toInt(16))
            } catch (ex: TransportTimeoutException) {
                // A missing prompt means adapter state is unknown. Stop instead of mixing late replies.
                throw IllegalStateException("Adaptertimeout bij ${request.command}; verbreek en verbind opnieuw", ex)
            }
            val decoded = if (result.ok) EvDecoder.decode(v, request.command, result.data.drop(request.responsePrefix.size)) else EvDecoded()
            metrics.addAll(decoded.metrics); cells.putAll(decoded.cells)
            val useful = decoded.metrics.isNotEmpty() || decoded.cells.isNotEmpty()
            details.add("${request.target.tx}/${request.target.rx} ${request.command}: " +
                (if (useful) "OK" else result.nrc ?: "onbekende lengte of ongeldige meetwaarde") + "\n${result.raw}")
            // Do not spend eight minutes probing 96 absent ZE50 cell DIDs.
            if (v == EvVehicle.ZOE_PH2 && request.command.drop(2).toInt(16) in EvRequests.zoe50CellDids) {
                missedCells = if (decoded.cells.isEmpty()) missedCells + 1 else 0
                if (missedCells >= 3) { details.add("Cel-uitlezing gestopt na drie ontbrekende antwoorden."); break }
            }
        }
        if (includeCells) details.add("${cells.size}/96 geldige celspanningen; celverschil vereist alle 96.")
        return EvSnapshot(v, System.currentTimeMillis(), metrics, cells, details)
    }
}
