package cc.monomer.metricflow.domain.datatable

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetricFlowDataTableTest {

    @Test
    fun `createFromRows infers column types from first non-null value`() {
        val table = MetricFlowDataTable.createFromRows(
            columnNames = listOf("user", "amount"),
            rows = listOf(
                listOf("a", null),
                listOf("b", 1.5),
            ),
        )
        assertEquals(CellType.STRING, table.columnDescriptions[0].columnType)
        assertEquals(CellType.DOUBLE, table.columnDescriptions[1].columnType)
        assertEquals(2, table.rowCount)
    }

    @Test
    fun `createFromRows coerces Java types`() {
        val table = MetricFlowDataTable.createFromRows(
            columnNames = listOf("ts", "v_int", "v_decimal"),
            rows = listOf(
                listOf(LocalDate.of(2026, 1, 1), 42, BigDecimal("1.23")),
            ),
        )
        assertEquals(CellType.DATE_TIME, table.columnDescriptions[0].columnType)
        // Int is coerced to Double.
        assertEquals(CellType.DOUBLE, table.columnDescriptions[1].columnType)
        assertEquals(CellType.DECIMAL, table.columnDescriptions[2].columnType)

        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), table.cellValue(0, 0))
        assertEquals(42.0, table.cellValue(0, 1))
    }

    @Test
    fun `mismatched row width is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MetricFlowDataTable.createFromRows(
                columnNames = listOf("a", "b"),
                rows = listOf(listOf("only-one")),
            )
        }
    }

    @Test
    fun `mixed types in a column are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MetricFlowDataTable.createFromRows(
                columnNames = listOf("c"),
                rows = listOf(listOf("string"), listOf(1.0)),
            )
        }
    }

    @Test
    fun `columnNameIndex resolves names`() {
        val table = MetricFlowDataTable.createFromRows(
            columnNames = listOf("a", "b"),
            rows = listOf(listOf("x", "y")),
        )
        assertEquals(0, table.columnNameIndex("a"))
        assertEquals(1, table.columnNameIndex("b"))
        assertFailsWith<IllegalArgumentException> { table.columnNameIndex("nope") }
    }

    @Test
    fun `withLowerCaseColumnNames lowercases headers`() {
        val table = MetricFlowDataTable.createFromRows(
            columnNames = listOf("UserId", "Amount"),
            rows = emptyList(),
        )
        val lowered = table.withLowerCaseColumnNames()
        assertEquals(listOf("userid", "amount"), lowered.columnNames)
    }

    @Test
    fun `sorted produces deterministic order`() {
        val table = MetricFlowDataTable.createFromRows(
            columnNames = listOf("b", "a"),
            rows = listOf(
                listOf(2.0, "z"),
                listOf(1.0, "y"),
            ),
        )
        val sorted = table.sorted()
        assertEquals(listOf("a", "b"), sorted.columnNames)
        assertEquals(listOf("y", 1.0), sorted.rows[0])
        assertEquals(listOf("z", 2.0), sorted.rows[1])
    }

    @Test
    fun `textFormat round-trips tab-separated cells`() {
        val table = MetricFlowDataTable.createFromRows(
            columnNames = listOf("user", "amount"),
            rows = listOf(listOf("alice", 10.0)),
        )
        val text = table.textFormat()
        assertTrue(text.startsWith("user\tamount"))
        assertTrue(text.contains("alice\t10.0"))
    }

    @Test
    fun `contentEquals returns true for structurally equal tables`() {
        val a = MetricFlowDataTable.createFromRows(
            columnNames = listOf("c"),
            rows = listOf(listOf("x"), listOf("y")),
        )
        val b = MetricFlowDataTable.createFromRows(
            columnNames = listOf("c"),
            rows = listOf(listOf("x"), listOf("y")),
        )
        assertTrue(a.contentEquals(b))
    }
}

class ColumnTypeTest {
    @Test
    fun `forValue maps known types`() {
        assertEquals(CellType.STRING, CellType.forValue("x"))
        assertEquals(CellType.BOOLEAN, CellType.forValue(true))
        assertEquals(CellType.DOUBLE, CellType.forValue(1.5))
        assertEquals(CellType.DECIMAL, CellType.forValue(BigDecimal.ONE))
        assertEquals(CellType.NULL, CellType.forValue(null))
        assertEquals(CellType.DATE_TIME, CellType.forValue(LocalDateTime.now()))
    }

    @Test
    fun `forValue rejects unsupported types`() {
        assertFailsWith<IllegalArgumentException> { CellType.forValue(listOf(1, 2)) }
    }

    @Test
    fun `rowCellTypes returns per-cell types`() {
        val row: List<CellValue> = listOf("a", 1.0, null)
        assertEquals(listOf(CellType.STRING, CellType.DOUBLE, CellType.NULL), rowCellTypes(row))
    }
}
