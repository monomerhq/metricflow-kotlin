package cc.monomer.metricflow.domain.spec.bind

import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SqlTableTest {

    @Test
    fun `from two-part string parses schema and table`() {
        val table = SqlTable.fromString("myschema.mytable")
        assertEquals("myschema", table.schemaName)
        assertEquals("mytable", table.tableName)
        assertNull(table.dbName)
    }

    @Test
    fun `from three-part string parses db schema and table`() {
        val table = SqlTable.fromString("mydb.myschema.mytable")
        assertEquals("mydb", table.dbName)
        assertEquals("myschema", table.schemaName)
        assertEquals("mytable", table.tableName)
    }

    @Test
    fun `one-part string is rejected`() {
        assertFailsWith<IllegalArgumentException> { SqlTable.fromString("just_table") }
    }

    @Test
    fun `four-part string is rejected`() {
        assertFailsWith<IllegalArgumentException> { SqlTable.fromString("a.b.c.d") }
    }

    @Test
    fun `sql renders the canonical dotted form for two-part`() {
        val table = SqlTable(schemaName = "ana", tableName = "events")
        assertEquals("ana.events", table.sql)
    }

    @Test
    fun `sql renders the canonical dotted form for three-part`() {
        val table = SqlTable(dbName = "warehouse", schemaName = "ana", tableName = "events")
        assertEquals("warehouse.ana.events", table.sql)
    }

    @Test
    fun `db without schema is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SqlTable(dbName = "warehouse", schemaName = null, tableName = "events")
        }
    }

    @Test
    fun `fromNodeRelation parses the NodeRelation relation name`() {
        val nodeRelation = NodeRelation(
            alias = "events",
            schemaName = "ana",
            database = null,
            relationName = "ana.events",
        )
        val table = SqlTable.fromNodeRelation(nodeRelation)
        assertEquals("ana", table.schemaName)
        assertEquals("events", table.tableName)
    }

    @Test
    fun `tables are ordered by schema then table`() {
        val a = SqlTable(schemaName = "a", tableName = "z")
        val b = SqlTable(schemaName = "b", tableName = "a")
        val sorted = listOf(b, a).sorted()
        assertEquals(listOf(a, b), sorted)
    }
}
