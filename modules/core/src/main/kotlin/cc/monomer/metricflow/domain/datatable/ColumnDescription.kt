package cc.monomer.metricflow.domain.datatable

/**
 * Describes a single column in a [MetricFlowDataTable].
 *
 * Port of `metricflow.data_table.mf_column.ColumnDescription`. In Python the
 * `column_type` is a Python type object (`int`, `str`, ...); we use our
 * [CellType] enum instead, which captures the same discrimination without
 * relying on JVM `Class` reflection.
 */
data class ColumnDescription(val columnName: String, val columnType: CellType) {

    /** Returns a copy with [columnName] lowercased — used by adapters that normalize headers. */
    fun withLowerCaseColumnName(): ColumnDescription =
        copy(columnName = columnName.lowercase())
}

/**
 * Describes a collection of columns in a data table.
 *
 * Port of `metricflow.data_table.mf_column.ColumnDescriptionSet`.
 */
data class ColumnDescriptionSet(val columnDescriptions: List<ColumnDescription>) : Iterable<ColumnDescription> {

    override fun iterator(): Iterator<ColumnDescription> = columnDescriptions.iterator()

    val columnNames: List<String> by lazy { columnDescriptions.map { it.columnName } }
    val columnTypes: List<CellType> by lazy { columnDescriptions.map { it.columnType } }
}
