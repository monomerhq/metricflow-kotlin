package cc.monomer.metricflow.domain.dataflow.instance

import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.spec.AggregationState
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceSetTest {

    private val simpleMetric = SimpleMetricInputInstance(
        associatedColumns = listOf(ColumnAssociation.ofSingle("bookings")),
        definedFrom = listOf(
            SemanticModelElementReference(
                semanticModelName = "bookings_source",
                elementName = "bookings",
            ),
        ),
        spec = SimpleMetricInputSpec(elementName = "bookings", fillNullsWith = null),
        aggregationState = AggregationState.NON_AGGREGATED,
    )

    private val dim = DimensionInstance(
        associatedColumns = listOf(ColumnAssociation.ofSingle("is_instant")),
        definedFrom = listOf(
            SemanticModelElementReference(
                semanticModelName = "bookings_source",
                elementName = "is_instant",
            ),
        ),
        spec = DimensionSpec(
            elementName = "is_instant",
            entityLinks = emptyList(),
            alias = null,
        ),
    )

    private val entity = EntityInstance(
        associatedColumns = listOf(ColumnAssociation.ofSingle("listing")),
        definedFrom = listOf(
            SemanticModelElementReference(
                semanticModelName = "bookings_source",
                elementName = "listing",
            ),
        ),
        spec = EntitySpec(elementName = "listing", entityLinks = emptyList(), alias = null),
    )

    private val metric = MetricInstance(
        associatedColumns = listOf(ColumnAssociation.ofSingle("bookings")),
        spec = MetricSpec(
            elementName = "bookings",
            whereFilterSpecs = emptyList(),
            alias = null,
            offsetWindow = null,
            offsetToGrain = null,
        ),
        definedFrom = MetricModelReference("bookings"),
    )

    private fun setOf(
        simpleMetricInputs: List<SimpleMetricInputInstance> = emptyList(),
        dimensions: List<DimensionInstance> = emptyList(),
        timeDimensions: List<TimeDimensionInstance> = emptyList(),
        entities: List<EntityInstance> = emptyList(),
        groupByMetrics: List<GroupByMetricInstance> = emptyList(),
        metrics: List<MetricInstance> = emptyList(),
        metadata: List<MetadataInstance> = emptyList(),
    ): InstanceSet = InstanceSet(
        simpleMetricInputInstances = simpleMetricInputs,
        dimensionInstances = dimensions,
        timeDimensionInstances = timeDimensions,
        entityInstances = entities,
        groupByMetricInstances = groupByMetrics,
        metricInstances = metrics,
        metadataInstances = metadata,
    )

    @Test
    fun `specSet projects all variants into the spec-only view`() {
        val set = setOf(
            simpleMetricInputs = listOf(simpleMetric),
            dimensions = listOf(dim),
            entities = listOf(entity),
            metrics = listOf(metric),
        )
        val specSet = set.specSet
        assertEquals(listOf(simpleMetric.spec), specSet.simpleMetricInputSpecs)
        assertEquals(listOf(dim.spec), specSet.dimensionSpecs)
        assertEquals(listOf(entity.spec), specSet.entitySpecs)
        assertEquals(listOf(metric.spec), specSet.metricSpecs)
    }

    @Test
    fun `merge dedupes by spec across sets`() {
        val a = setOf(simpleMetricInputs = listOf(simpleMetric))
        val b = setOf(
            simpleMetricInputs = listOf(simpleMetric.copy(aggregationState = AggregationState.PARTIAL)),
            dimensions = listOf(dim),
        )
        val merged = InstanceSet.merge(listOf(a, b))
        assertEquals(1, merged.simpleMetricInputInstances.size, "Duplicate spec should be deduped")
        assertEquals(
            AggregationState.NON_AGGREGATED,
            merged.simpleMetricInputInstances[0].aggregationState,
            "First-seen instance wins on dedup",
        )
        assertEquals(listOf(dim), merged.dimensionInstances)
    }

    @Test
    fun `groupInstancesByType buckets each variant`() {
        val set = InstanceSet.groupInstancesByType(
            listOf(simpleMetric, dim, entity, metric),
        )
        assertEquals(1, set.simpleMetricInputInstances.size)
        assertEquals(1, set.dimensionInstances.size)
        assertEquals(1, set.entityInstances.size)
        assertEquals(1, set.metricInstances.size)
        assertEquals(0, set.timeDimensionInstances.size)
    }

    @Test
    fun `linkableInstances exposes only the linkable variants`() {
        val set = setOf(
            simpleMetricInputs = listOf(simpleMetric),
            dimensions = listOf(dim),
            entities = listOf(entity),
            metrics = listOf(metric),
        )
        val linkable = set.linkableInstances
        // simple-metric inputs / metrics are non-linkable; entities / dims are.
        assertEquals(2, linkable.size)
        // The variants in `linkable` are the dimension instance and the entity instance.
        assertTrue(linkable.any { it.spec == dim.spec })
        assertTrue(linkable.any { it.spec == entity.spec })
    }

    @Test
    fun `withoutSimpleMetricInputs drops the simple metric inputs only`() {
        val set = setOf(
            simpleMetricInputs = listOf(simpleMetric),
            dimensions = listOf(dim),
        )
        val stripped = set.withoutSimpleMetricInputs()
        assertEquals(emptyList(), stripped.simpleMetricInputInstances)
        assertEquals(listOf(dim), stripped.dimensionInstances)
    }
}
