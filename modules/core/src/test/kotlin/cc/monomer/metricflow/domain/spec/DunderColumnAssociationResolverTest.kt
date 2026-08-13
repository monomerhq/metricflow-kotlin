package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowFunction
import kotlin.test.Test
import kotlin.test.assertEquals

class DunderColumnAssociationResolverTest {

    private val resolver = DunderColumnAssociationResolver(dunderPrefixSimpleMetricInputs = true)

    @Test
    fun `dimension with entity link resolves to listing__country`() {
        val spec = DimensionSpec(elementName = "country", entityLinks = listOf(EntityReference("listing")), alias = null)
        assertEquals("listing__country", resolver.resolveSpec(spec).columnName)
    }

    @Test
    fun `alias overrides the dunder name`() {
        val spec = DimensionSpec(elementName = "country", entityLinks = listOf(EntityReference("listing")), alias = "iso")
        assertEquals("iso", resolver.resolveSpec(spec).columnName)
    }

    @Test
    fun `time dimension appends agg_state and window functions`() {
        val spec = TimeDimensionSpec(
            elementName = "ds",
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY),
            datePart = null,
            aggregationState = AggregationState.COMPLETE,
            windowFunctions = listOf(SqlWindowFunction.FIRST_VALUE),
            alias = null,
        )
        assertEquals("ds__day__complete__first_value", resolver.resolveSpec(spec).columnName)
    }

    @Test
    fun `simple metric input gets dunder prefix when enabled`() {
        val spec = SimpleMetricInputSpec("bookings", null)
        assertEquals("__bookings", resolver.resolveSpec(spec).columnName)
    }

    @Test
    fun `simple metric input drops dunder prefix when disabled`() {
        val resolverNoPrefix = DunderColumnAssociationResolver(dunderPrefixSimpleMetricInputs = false)
        val spec = SimpleMetricInputSpec("bookings", null)
        assertEquals("bookings", resolverNoPrefix.resolveSpec(spec).columnName)
    }

    @Test
    fun `metric uses alias when set otherwise element name`() {
        assertEquals("bookings", resolver.resolveSpec(MetricSpec.fromElementName("bookings")).columnName)
        assertEquals("bk", resolver.resolveSpec(MetricSpec.fromElementName("bookings").withAlias("bk")).columnName)
    }

    @Test
    fun `metadata uses agg-suffixed dunder name`() {
        val spec = MetadataSpec("row_count", aggType = cc.monomer.metricflow.domain.manifest.model.enums.AggregationType.MAX)
        assertEquals("row_count__max", resolver.resolveSpec(spec).columnName)
    }

    @Test
    fun `withOptions returns new resolver with toggled flag`() {
        val toggled = resolver.withOptions(dunderPrefixSimpleMetricInputs = false)
        assertEquals(
            "bookings",
            toggled.resolveSpec(SimpleMetricInputSpec("bookings", null)).columnName,
        )
    }
}
