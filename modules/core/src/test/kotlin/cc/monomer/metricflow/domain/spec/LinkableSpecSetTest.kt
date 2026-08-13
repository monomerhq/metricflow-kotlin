package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkableSpecSetTest {

    private fun dim(name: String, link: String? = null) = DimensionSpec(
        elementName = name,
        entityLinks = link?.let { listOf(EntityReference(it)) } ?: emptyList(),
        alias = null,
    )

    private fun entity(name: String) = EntitySpec(elementName = name, entityLinks = emptyList(), alias = null)

    private fun metricTime(grain: TimeGranularity) = TimeDimensionSpec(
        elementName = METRIC_TIME_ELEMENT_NAME,
        entityLinks = emptyList(),
        timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(grain),
        datePart = null,
        aggregationState = null,
        windowFunctions = emptyList(),
        alias = null,
    )

    @Test
    fun `EMPTY is the merge identity`() {
        val s = LinkableSpecSet(
            dimensionSpecs = listOf(dim("a")),
            timeDimensionSpecs = emptyList(),
            entitySpecs = emptyList(),
            groupByMetricSpecs = emptyList(),
        )
        assertEquals(s, LinkableSpecSet.EMPTY.merge(s))
        assertEquals(s, s.merge(LinkableSpecSet.EMPTY))
    }

    @Test
    fun `merge concatenates buckets`() {
        val a = LinkableSpecSet(
            dimensionSpecs = listOf(dim("a")),
            timeDimensionSpecs = listOf(metricTime(TimeGranularity.DAY)),
            entitySpecs = emptyList(),
            groupByMetricSpecs = emptyList(),
        )
        val b = LinkableSpecSet(
            dimensionSpecs = listOf(dim("b")),
            timeDimensionSpecs = emptyList(),
            entitySpecs = listOf(entity("user")),
            groupByMetricSpecs = emptyList(),
        )
        val merged = a.merge(b)
        assertEquals(listOf(dim("a"), dim("b")), merged.dimensionSpecs)
        assertEquals(listOf(entity("user")), merged.entitySpecs)
        assertEquals(listOf(metricTime(TimeGranularity.DAY)), merged.timeDimensionSpecs)
    }

    @Test
    fun `dedupe removes duplicates preserving order`() {
        val s = LinkableSpecSet(
            dimensionSpecs = listOf(dim("a"), dim("a"), dim("b")),
            timeDimensionSpecs = emptyList(),
            entitySpecs = emptyList(),
            groupByMetricSpecs = emptyList(),
        )
        assertEquals(listOf(dim("a"), dim("b")), s.dedupe().dimensionSpecs)
    }

    @Test
    fun `containsMetricTime reflects metric_time spec presence`() {
        val empty = LinkableSpecSet.EMPTY
        assertFalse(empty.containsMetricTime)
        val withMt = empty.copy(timeDimensionSpecs = listOf(metricTime(TimeGranularity.DAY)))
        assertTrue(withMt.containsMetricTime)
    }

    @Test
    fun `createFromSpecs partitions by variant`() {
        val specs: List<LinkableInstanceSpec> = listOf(
            dim("a"),
            entity("e"),
            metricTime(TimeGranularity.DAY),
        )
        val s = LinkableSpecSet.createFromSpecs(specs)
        assertEquals(1, s.dimensionSpecs.size)
        assertEquals(1, s.entitySpecs.size)
        assertEquals(1, s.timeDimensionSpecs.size)
    }

    @Test
    fun `withoutAliases strips alias from every spec`() {
        val s = LinkableSpecSet(
            dimensionSpecs = listOf(DimensionSpec("a", emptyList(), "alias_a")),
            timeDimensionSpecs = emptyList(),
            entitySpecs = listOf(EntitySpec("e", emptyList(), "alias_e")),
            groupByMetricSpecs = emptyList(),
        )
        val stripped = s.withoutAliases
        assertEquals(null, stripped.dimensionSpecs.first().alias)
        assertEquals(null, stripped.entitySpecs.first().alias)
    }

    @Test
    fun `replaceCustomGranularityWithBaseGranularity lifts custom to base`() {
        val custom = TimeDimensionSpec(
            elementName = "ds",
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity("fiscal_q", TimeGranularity.QUARTER),
            datePart = null,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )
        val s = LinkableSpecSet(emptyList(), listOf(custom), emptyList(), emptyList())
        val lifted = s.replaceCustomGranularityWithBaseGranularity()
        assertEquals(TimeGranularity.QUARTER.value, lifted.timeDimensionSpecs.first().timeGranularityName)
    }

    @Test
    fun `InstanceSpecSet linkable view picks linkable buckets`() {
        val instanceSet = InstanceSpecSet(
            metricSpecs = listOf(MetricSpec.fromElementName("bookings")),
            simpleMetricInputSpecs = listOf(SimpleMetricInputSpec("bookings_inp", null)),
            dimensionSpecs = listOf(dim("a")),
            entitySpecs = listOf(entity("e")),
            timeDimensionSpecs = emptyList(),
            groupByMetricSpecs = emptyList(),
            metadataSpecs = emptyList(),
        )
        assertEquals(2, instanceSet.linkableSpecs.size)
    }

    @Test
    fun `InstanceSpecSet merge is concatenation`() {
        val a = InstanceSpecSet.EMPTY.copy(metricSpecs = listOf(MetricSpec.fromElementName("m1")))
        val b = InstanceSpecSet.EMPTY.copy(metricSpecs = listOf(MetricSpec.fromElementName("m2")))
        assertEquals(2, a.merge(b).metricSpecs.size)
    }

    @Test
    fun `InstanceSpecSet transform dispatches via interface`() {
        val s = InstanceSpecSet.EMPTY.copy(dimensionSpecs = listOf(dim("a"), dim("b")))
        val names = s.transform(ToElementNameSet)
        assertEquals(setOf("a", "b"), names)
    }
}
