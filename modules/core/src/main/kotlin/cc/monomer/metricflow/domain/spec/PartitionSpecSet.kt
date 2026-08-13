package cc.monomer.metricflow.domain.spec

/**
 * Grouping of the linkable specs used for partition-style joins.
 *
 * Port of `metricflow_semantics.specs.partition_spec_set.PartitionSpecSet`.
 *
 * Used by the dataflow planner to capture partition dimensions on a
 * per-semantic-model basis: when joining two models, partition columns must
 * line up so the join doesn't fan out the row count.
 */
data class PartitionSpecSet(
    val dimensionSpecs: List<DimensionSpec>,
    val timeDimensionSpecs: List<TimeDimensionSpec>,
) {
    companion object {
        /** Empty set — both lists empty. */
        val EMPTY: PartitionSpecSet = PartitionSpecSet(emptyList(), emptyList())
    }
}
