package cc.monomer.metricflow.domain.datatable

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * In-memory tabular result.
 *
 * Port of `metricflow.data_table.mf_table.MetricFlowDataTable`. The class is
 * used as a parameter type for `SqlClient.query()` in Python; the Kotlin
 * port keeps the type even though the engine does not execute SQL — every
 * caller that references the type can still compile.
 *
 * Equality is intentionally identity-based (`@DataClass`-style) — Python
 * also disables structural equality because rows may contain NaNs. Callers
 * comparing tables should use [contentEquals].
 *
 * Construction is normally via [createFromRows] which validates the row
 * width and coerces input values to the canonical [CellValue] shape.
 */
class MetricFlowDataTable private constructor(
    val columnDescriptions: List<ColumnDescription>,
    val rows: List<List<CellValue>>,
) {

    init {
        val expectedCount = columnCount
        for ((rowIndex, row) in rows.withIndex()) {
            require(row.size == expectedCount) {
                "Row at index $rowIndex has ${row.size} columns instead of $expectedCount."
            }
            for ((columnIndex, cellValue) in row.withIndex()) {
                val expectedType = columnDescriptions[columnIndex].columnType
                if (cellValue == null) continue
                val actualType = CellType.forValue(cellValue)
                require(actualType == expectedType || expectedType == CellType.NULL) {
                    "Cell value type mismatch at row=$rowIndex col=$columnIndex: " +
                        "expected $expectedType, actual $actualType (value=$cellValue)"
                }
                if (cellValue is LocalDateTime) {
                    // Python rejects timezones — LocalDateTime is already tz-naive in Kotlin/Java.
                }
            }
        }
    }

    val columnCount: Int get() = columnDescriptions.size
    val rowCount: Int get() = rows.size
    val columnNames: List<String> get() = columnDescriptions.map { it.columnName }

    /** Returns the index of the column matching [columnName]; throws if unknown. */
    fun columnNameIndex(columnName: String): Int {
        val idx = columnDescriptions.indexOfFirst { it.columnName == columnName }
        if (idx >= 0) return idx
        throw IllegalArgumentException("Unknown column name '$columnName'. Known: $columnNames")
    }

    /** Iterates the values of one column without allocating a new list. */
    fun columnValuesIterator(columnIndex: Int): Iterator<CellValue> =
        iterator { for (row in rows) yield(row[columnIndex]) }

    fun cellValue(rowIndex: Int, columnIndex: Int): CellValue = rows[rowIndex][columnIndex]

    /** Returns a copy with column names lowercased. */
    fun withLowerCaseColumnNames(): MetricFlowDataTable = MetricFlowDataTable(
        columnDescriptions = columnDescriptions.map { it.withLowerCaseColumnName() },
        rows = rows,
    )

    /**
     * Returns a copy with columns sorted alphabetically and rows sorted by
     * their stringified contents. Mirrors Python's `.sorted()`.
     */
    fun sorted(): MetricFlowDataTable = sortedByColumnName().sortedByRow()

    private fun sortedByColumnName(): MetricFlowDataTable {
        val sortedNames = columnNames.sorted()
        val nameToOldIdx = sortedNames.map { columnNameIndex(it) }
        val newRows = rows.map { row -> nameToOldIdx.map { idx -> row[idx] } }
        val newCols = sortedNames.map { columnDescriptions[columnNameIndex(it)] }
        return MetricFlowDataTable(newCols, newRows)
    }

    private fun sortedByRow(): MetricFlowDataTable {
        val sortedRows = rows.sortedWith(compareBy { row ->
            row.joinToString("|") { cell ->
                when (cell) {
                    null -> ""
                    is LocalDateTime -> cell.toString()
                    else -> cell.toString()
                }
            }
        })
        return MetricFlowDataTable(columnDescriptions, sortedRows)
    }

    /**
     * Returns true if [other] has the same column descriptions and rows in
     * the same order. Equivalent to Python's `check_data_tables_are_equal`
     * for the common case (NaN handling left to callers).
     */
    fun contentEquals(other: MetricFlowDataTable): Boolean {
        if (columnDescriptions != other.columnDescriptions) return false
        if (rows.size != other.rows.size) return false
        for (i in rows.indices) {
            if (rows[i] != other.rows[i]) return false
        }
        return true
    }

    /**
     * Returns a text version of this table that is suitable for printing.
     *
     * The Python implementation uses the `tabulate` package; here we keep a
     * simpler tab-separated rendering since the engine itself never reads
     * the formatted output.
     */
    fun textFormat(): String {
        val sb = StringBuilder()
        sb.append(columnNames.joinToString("\t")).append('\n')
        for (row in rows) {
            sb.append(row.joinToString("\t") { cell ->
                when (cell) {
                    null -> ""
                    is LocalDateTime -> cell.toString()
                    else -> cell.toString()
                }
            }).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    companion object {
        /**
         * Builds a [MetricFlowDataTable] from raw rows. Performs the same
         * input-type coercion as the Python `_MetricFlowDataTableBuilder`
         * (Decimal→BigDecimal, Date→LocalDateTime midnight, Int→Long, etc.)
         * and figures out each column's [CellType] from the first non-null
         * value seen.
         */
        fun createFromRows(columnNames: List<String>, rows: Iterable<List<InputCellValue>>): MetricFlowDataTable {
            val coercedRows: List<List<CellValue>> = rows.map { row ->
                require(row.size == columnNames.size) {
                    "Input row has ${row.size} columns, but expected ${columnNames.size}."
                }
                row.map { coerce(it) }
            }

            // Determine each column's type from the first non-null value seen.
            val columnTypes = MutableList(columnNames.size) { CellType.NULL }
            for (row in coercedRows) {
                for ((idx, value) in row.withIndex()) {
                    if (value == null) continue
                    val cellType = CellType.forValue(value)
                    val current = columnTypes[idx]
                    columnTypes[idx] = when {
                        current == CellType.NULL -> cellType
                        current == cellType -> current
                        else -> throw IllegalArgumentException(
                            "Expected cell type $current at column $idx but got $cellType",
                        )
                    }
                }
            }
            val columns = columnNames.zip(columnTypes) { name, type -> ColumnDescription(name, type) }
            return MetricFlowDataTable(columns, coercedRows)
        }

        /** Coerces a Python-input cell value to the canonical [CellValue] shape. */
        private fun coerce(value: InputCellValue): CellValue = when (value) {
            null -> null
            is Boolean -> value
            is BigDecimal -> value
            is Double -> value
            is Float -> value.toDouble()
            is Long -> value.toDouble()
            is Int -> value.toDouble()
            is LocalDateTime -> value
            is LocalDate -> value.atStartOfDay()
            is String -> value
            else -> throw IllegalArgumentException("Row cell has unexpected type: ${value::class.qualifiedName}")
        }
    }
}
