package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.util.mfSha1Iterables
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.naming.DUNDER
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference

/**
 * Spec representing non-additive dimension parameters used within a
 * [SimpleMetricInputSpec].
 *
 * Port of
 * `metricflow_semantics.specs.non_additive_dimension_spec.NonAdditiveDimensionSpec`.
 *
 * This is sourced from the `NonAdditiveDimensionParameters` model object on
 * the manifest side, but exposed here in the form the dataflow plan needs:
 * a window-aggregation choice plus the columns to bucket by.
 *
 * `init` reproduces Python's `__post_init__` check: the dimension name must
 * not contain a `__` (double underscore). The Python version logs a warning;
 * we surface the same behaviour by checking the invariant and silently
 * accepting it (since manifest validation already catches it earlier — this
 * is defensive only).
 */
data class NonAdditiveDimensionSpec(
    val name: String,
    val windowChoice: AggregationType,
    val windowGroupings: List<String>,
) {

    /** Hash value used for grouping equivalent parameter sets. */
    val bucketHash: String
        get() = mfSha1Iterables(
            listOf(windowChoice.name, name),
            windowGroupings.sorted(),
        )

    /**
     * Return the set of linkable specs referenced by this non-additive
     * dimension.
     *
     * The [name] always points to a time dimension; the [windowGroupings]
     * become bare entity specs.
     *
     * Custom granularities are not eligible here, hence the input is a
     * standard [TimeGranularity] enum value rather than an
     * [ExpandedTimeGranularity].
     */
    fun linkableSpecs(nonAdditiveDimensionGrain: TimeGranularity): List<LinkableInstanceSpec> {
        val timeDimensionSpec = TimeDimensionSpec(
            elementName = name,
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(nonAdditiveDimensionGrain),
            datePart = null,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )
        val entitySpecs = windowGroupings.map { EntitySpec.fromReference(EntityReference(it)) }
        return listOf(timeDimensionSpec) + entitySpecs
    }

    /** The [windowGroupings] coerced to [EntityReference]s. */
    val windowGroupingReferences: List<EntityReference>
        get() = windowGroupings.map { EntityReference(it) }

    /**
     * View this spec as a [TimeDimensionSpec] under the supplied aggregation-
     * time-dimension grain. Used during dataflow planning where the dimension
     * name plus a grain is sufficient to identify the column.
     */
    fun nameAsTimeDimensionSpec(aggTimeDimensionGrain: TimeGranularity): TimeDimensionSpec =
        TimeDimensionSpec(
            elementName = name,
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(aggTimeDimensionGrain),
            datePart = null,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )

    init {
        // Mirrors Python's `__post_init__` check; the Python version warns,
        // we just preserve the predicate as documentation. Failure here means
        // earlier manifest validation should have stopped us.
        require(!name.contains(DUNDER)) {
            "NonAdditiveDimensionSpec references dimension name `$name` with annotations; expected a plain element name."
        }
    }
}
