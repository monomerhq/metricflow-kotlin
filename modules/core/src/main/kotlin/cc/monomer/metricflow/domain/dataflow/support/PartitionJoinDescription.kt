package cc.monomer.metricflow.domain.dataflow.support

import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * Describes which partition dimension columns should be joined between two nodes.
 *
 * Port of `metricflow.dataflow.builder.partitions.PartitionDimensionJoinDescription`. Referenced
 * by [cc.monomer.metricflow.domain.dataflow.nodes.JoinDescription]. The resolver that
 * computes these descriptions (`PartitionJoinResolver`) is part of W9b.
 */
data class PartitionDimensionJoinDescription(
    val startNodeDimensionSpec: DimensionSpec,
    val nodeToJoinDimensionSpec: DimensionSpec,
)

/**
 * Describes which partition **time** dimension columns should be joined between two nodes.
 *
 * Port of `metricflow.dataflow.builder.partitions.PartitionTimeDimensionJoinDescription`.
 */
data class PartitionTimeDimensionJoinDescription(
    val startNodeTimeDimensionSpec: TimeDimensionSpec,
    val nodeToJoinTimeDimensionSpec: TimeDimensionSpec,
)
