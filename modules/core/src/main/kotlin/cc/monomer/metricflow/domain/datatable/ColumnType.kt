package cc.monomer.metricflow.domain.datatable

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Cell values accepted by a [MetricFlowDataTable].
 *
 * Port of `metricflow.data_table.column_types.CellValue`. The Python type is
 * a `Union[Decimal, float, str, datetime, bool, None]`; Kotlin doesn't have
 * a union type, so we model the surface as a sealed family wrapping each
 * variant. Callers typically receive `Any?` from JDBC drivers and then go
 * through the [_MetricFlowDataTableBuilder] to normalize.
 *
 * The sealed [CellType] enum captures the column type discriminator and
 * matches the `cell_type(x)` helper.
 */
enum class CellType {
    DECIMAL,
    DOUBLE,
    STRING,
    DATE_TIME,
    BOOLEAN,
    NULL,
    ;

    companion object {
        /** Returns the [CellType] for a runtime cell value. */
        fun forValue(value: Any?): CellType = when (value) {
            null -> NULL
            is BigDecimal -> DECIMAL
            is Double -> DOUBLE
            is Float -> DOUBLE
            is String -> STRING
            is LocalDateTime -> DATE_TIME
            is Boolean -> BOOLEAN
            else -> throw IllegalArgumentException(
                "Row cell has unexpected type: ${value::class.qualifiedName}",
            )
        }
    }
}

/**
 * Input cell values — broader than [CellValue] because builders accept Java
 * types (`int`, `Decimal`, `LocalDate`) and convert to the canonical
 * [CellValue] shape.
 *
 * Port of `metricflow.data_table.column_types.InputCellValue`. The
 * conversion happens inside [MetricFlowDataTable.createFromRows].
 */
typealias InputCellValue = Any?

/**
 * Canonical cell value type stored inside a [MetricFlowDataTable].
 *
 * Permitted runtime types: [BigDecimal], [Double], [String], [LocalDateTime],
 * [Boolean], `null`. The builder coerces `Int`/`Long`/`Float`/`LocalDate` into
 * these.
 */
typealias CellValue = Any?

/** Returns the cell type / column type for each value in [row]. */
fun rowCellTypes(row: List<CellValue>): List<CellType> = row.map { CellType.forValue(it) }
