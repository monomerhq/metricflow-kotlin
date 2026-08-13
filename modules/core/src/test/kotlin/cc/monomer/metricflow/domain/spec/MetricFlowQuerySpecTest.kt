package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MetricFlowQuerySpecTest {

    private fun emptyQuerySpec() = MetricFlowQuerySpec(
        metricSpecs = emptyList(),
        dimensionSpecs = emptyList(),
        entitySpecs = emptyList(),
        timeDimensionSpecs = emptyList(),
        groupByMetricSpecs = emptyList(),
        orderBySpecs = emptyList(),
        timeRangeConstraint = null,
        limit = null,
        filterIntersection = WhereFilterIntersection(emptyList()),
        filterSpecResolutionLookup = null,
        minMaxOnly = false,
        applyGroupBy = true,
        inputSpecOrder = InputSpecOrder.EMPTY,
    )

    @Test
    fun `linkableSpecs aggregates linkable buckets`() {
        val q = emptyQuerySpec().copy(
            dimensionSpecs = listOf(DimensionSpec.fromElementName("country")),
            entitySpecs = listOf(EntitySpec.fromElementName("user")),
        )
        assertEquals(2, q.linkableSpecs.asTuple.size)
        assertEquals(1, q.linkableSpecs.dimensionSpecs.size)
        assertEquals(1, q.linkableSpecs.entitySpecs.size)
    }

    @Test
    fun `withTimeRangeConstraint preserves other fields`() {
        val q = emptyQuerySpec().copy(metricSpecs = listOf(MetricSpec.fromElementName("bookings")))
        val range = TimeRangeConstraint.allTime()
        val updated = q.withTimeRangeConstraint(range)
        assertEquals(range, updated.timeRangeConstraint)
        assertEquals(q.metricSpecs, updated.metricSpecs)
    }

    @Test
    fun `InputSpecOrder rejects duplicates`() {
        val m = MetricSpec.fromElementName("bookings")
        val d = DimensionSpec.fromElementName("country")
        assertFailsWith<IllegalStateException> {
            InputSpecOrder(
                groupByItemSpecs = listOf(d, d),
                metricSpecs = listOf(m),
            )
        }
        assertFailsWith<IllegalStateException> {
            InputSpecOrder(
                groupByItemSpecs = listOf(m, d),
                metricSpecs = listOf(m),
            )
        }
    }

    @Test
    fun `InputSpecOrder accepts disjoint lists`() {
        val m = MetricSpec.fromElementName("bookings")
        val d = DimensionSpec.fromElementName("country")
        InputSpecOrder(groupByItemSpecs = listOf(d), metricSpecs = listOf(m))
        // expect no throw
    }
}
