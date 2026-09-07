package cc.monomer.metricflow.domain.query.filter

import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.DunderColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WhereFilterTemplateRendererTest {
    private val renderer = WhereFilterTemplateRenderer(DunderColumnAssociationResolver(true), emptySet())
    private val customerPath = listOf(EntityReference("account_id"), EntityReference("customer_id"))

    @Test
    fun `dimension prefixes entity path in named and positional forms`() {
        for (call in listOf(
            "Dimension('customer_id__customer_name', entity_path=['account_id'])",
            "Dimension('customer_id__customer_name', ['account_id'])",
            "Dimension(entity_path=['account_id'], name='customer_id__customer_name')",
        )) {
            val rendered = renderer.render("{{ $call }} = 'Alice'")
            assertEquals("account_id__customer_id__customer_name = 'Alice'", rendered.whereSql)
            assertEquals(listOf(DimensionSpec("customer_name", customerPath, null)), rendered.usedSpecs)
        }
    }

    @Test
    fun `time dimension preserves path and grain in all supported forms`() {
        for (call in listOf(
            "TimeDimension('customer_id__created_at', 'month', ['account_id'])",
            "TimeDimension('customer_id__created_at', 'month', entity_path=['account_id'])",
            "TimeDimension('customer_id__created_at', entity_path=['account_id'], time_granularity_name='month')",
            "TimeDimension('customer_id__created_at__month', None, ['account_id'])",
            "TimeDimension('customer_id__created_at__month', entity_path=['account_id'])",
            "TimeDimension('customer_id__created_at', entity_path=['account_id']).grain('MONTH')",
            "Dimension('customer_id__created_at', ['account_id']).grain('MONTH')",
        )) {
            val rendered = renderer.render("{{ $call }} >= '2026-01-01'")
            assertEquals("account_id__customer_id__created_at__month >= '2026-01-01'", rendered.whereSql)
            val spec = assertIs<TimeDimensionSpec>(rendered.usedSpecs.single())
            assertEquals(customerPath, spec.entityLinks)
            assertEquals("created_at", spec.elementName)
            assertEquals("month", spec.timeGranularity?.name)
        }
    }

    @Test
    fun `entity prefixes existing links without flattening path items`() {
        for (call in listOf(
            "Entity('customer_id', entity_path=['account_id'])",
            "Entity('customer_id', ['account_id'])",
            "Entity(entity_name='customer_id', entity_path=['account_id'])",
        )) {
            val rendered = renderer.render("{{ $call }} IS NOT NULL")
            assertEquals("account_id__customer_id IS NOT NULL", rendered.whereSql)
            assertEquals(listOf(EntitySpec("customer_id", listOf(EntityReference("account_id")), null)), rendered.usedSpecs)
        }
        val rendered = renderer.render("{{ Entity('customer_id', ['source_id', 'account_id']) }}")
        assertEquals(listOf(EntityReference("source_id"), EntityReference("account_id")), rendered.usedSpecs.single().entityLinks)
    }

    @Test
    fun `empty path and single hop preserve existing specs`() {
        for (call in listOf("Dimension('customer_id__customer_name')", "Dimension('customer_id__customer_name', [])")) {
            val rendered = renderer.render("{{ $call }}")
            assertEquals("customer_id__customer_name", rendered.whereSql)
            assertEquals(listOf(DimensionSpec("customer_name", listOf(EntityReference("customer_id")), null)), rendered.usedSpecs)
        }
        val rendered = renderer.render("{{ Dimension('metric_time').grain('month').date_part('month') }}")
        assertEquals(DatePart.MONTH, assertIs<TimeDimensionSpec>(rendered.usedSpecs.single()).datePart)
    }

    @Test
    fun `malformed duplicate unknown and wrongly typed arguments fail explicitly`() {
        for (call in listOf(
            "Dimension('customer_id__customer_name', entity_path='account_id')",
            "Dimension('customer_id__customer_name', entity_path=None)",
            "Dimension('customer_id__customer_name', entity_path=[account_id])",
            "Dimension('customer_id__customer_name', entity_path=[1])",
            "Dimension('customer_id__customer_name', entity_path=[['account_id']])",
            "Dimension('customer_id__customer_name', entity_path=['account_id'], entity_path=[])",
            "Dimension('customer_id__customer_name', ['account_id'], entity_path=[])",
            "Dimension('customer_id__customer_name', entity_path=[], ['account_id'])",
            "Dimension('customer_id__customer_name', path=['account_id'])",
            "Dimension('customer_id__customer_name', [], 'ignored')",
            "Dimension(['customer_id__customer_name'])",
            "Entity('customer_id', entity_path=[]) trailing",
            "Entity('customer_id', ['account_id']).grain('month')",
            "Entity('customer_id', entity_path=['account_id')",
            "TimeDimension('customer_id__created_at', ['account_id'])",
            "TimeDimension('customer_id__created_at', 'month', 'account_id')",
            "TimeDimension('customer_id__created_at', 'month', time_granularity_name='day')",
            "TimeDimension('customer_id__created_at__month', 'day')",
            "TimeDimension('customer_id__created_at', 'month', descending=None)",
            "Dimension('customer_id__created_at').grain('month', entity_path=[])",
        )) {
            assertFailsWith<IllegalArgumentException>(call) { renderer.render("{{ $call }}") }
        }
    }
}
