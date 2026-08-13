package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.dataflow.dataset.DataSet
import cc.monomer.metricflow.domain.dataflow.support.PartitionDimensionJoinDescription
import cc.monomer.metricflow.domain.dataflow.support.PartitionTimeDimensionJoinDescription
import cc.monomer.metricflow.domain.lookup.SemanticModelLookup
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.PartitionSpecSet
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * When joining datasets, figures out the partition columns that should be joined on.
 *
 * Port of `metricflow.dataflow.builder.partitions.PartitionJoinResolver`. Used by the node
 * evaluator (W9b) when constructing `JoinLinkableInstancesRecipe` entries — both nodes must
 * share partition columns so the join doesn't produce duplicate rows.
 */
class PartitionJoinResolver(private val semanticModelLookup: SemanticModelLookup) {

    private fun getPartitions(specSet: InstanceSpecSet): PartitionSpecSet {
        val partitionDimensionSpecs = specSet.dimensionSpecs.filter { spec ->
            semanticModelLookup.dimensionLookup.getInvariant(spec.reference).isPartition
        }
        val partitionTimeDimensionSpecs = specSet.timeDimensionSpecs.filter { spec ->
            spec.reference != DataSet.metricTimeDimensionReference() &&
                semanticModelLookup.dimensionLookup.getInvariant(spec.dimensionReference).isPartition
        }
        return PartitionSpecSet(
            dimensionSpecs = partitionDimensionSpecs,
            timeDimensionSpecs = partitionTimeDimensionSpecs,
        )
    }

    /** Figures out which partition dimensions to join on. Port of `resolve_partition_dimension_joins`. */
    fun resolvePartitionDimensionJoins(
        leftNodeSpecSet: InstanceSpecSet,
        nodeToJoinSpecSet: InstanceSpecSet,
    ): List<PartitionDimensionJoinDescription> {
        val startPartitions = getPartitions(leftNodeSpecSet)
        val joinPartitions = getPartitions(nodeToJoinSpecSet)
        val result = mutableListOf<PartitionDimensionJoinDescription>()

        val startElementNames = startPartitions.dimensionSpecs.map { it.elementName }.distinctPreservingOrder()
        for (elementName in startElementNames) {
            val startSpec = simplestDimensionSpec(
                startPartitions.dimensionSpecs.filter { it.elementName == elementName },
            )
            val joinElementNames = joinPartitions.dimensionSpecs.map { it.elementName }.distinctPreservingOrder()
            if (elementName in joinElementNames) {
                val joinSpec = simplestDimensionSpec(
                    joinPartitions.dimensionSpecs.filter { it.elementName == elementName },
                )
                result.add(
                    PartitionDimensionJoinDescription(
                        startNodeDimensionSpec = startSpec,
                        nodeToJoinDimensionSpec = joinSpec,
                    ),
                )
            }
        }
        return result
    }

    /** Figures out which partition time dimensions to join on. Port of `resolve_partition_time_dimension_joins`. */
    fun resolvePartitionTimeDimensionJoins(
        leftNodeSpecSet: InstanceSpecSet,
        nodeToJoinSpecSet: InstanceSpecSet,
    ): List<PartitionTimeDimensionJoinDescription> {
        val startPartitions = getPartitions(leftNodeSpecSet)
        val joinPartitions = getPartitions(nodeToJoinSpecSet)
        val result = mutableListOf<PartitionTimeDimensionJoinDescription>()

        val timeElementNames = startPartitions.timeDimensionSpecs.map { it.elementName }.distinctPreservingOrder()
        for (elementName in timeElementNames) {
            val startSpec = simplestTimeDimensionSpec(
                startPartitions.timeDimensionSpecs.filter { it.elementName == elementName },
            )
            val joinElementNames = joinPartitions.timeDimensionSpecs.map { it.elementName }.distinctPreservingOrder()
            if (elementName in joinElementNames) {
                val joinSpec = simplestTimeDimensionSpec(
                    joinPartitions.timeDimensionSpecs.filter { it.elementName == elementName },
                )
                result.add(
                    PartitionTimeDimensionJoinDescription(
                        startNodeTimeDimensionSpec = startSpec,
                        nodeToJoinTimeDimensionSpec = joinSpec,
                    ),
                )
            }
        }
        return result
    }

    private fun simplestDimensionSpec(dimensionSpecs: List<DimensionSpec>): DimensionSpec {
        check(dimensionSpecs.isNotEmpty())
        return dimensionSpecs.sortedBy { it.entityLinks.size }.first()
    }

    private fun simplestTimeDimensionSpec(timeDimensionSpecs: List<TimeDimensionSpec>): TimeDimensionSpec {
        check(timeDimensionSpecs.isNotEmpty())
        check(timeDimensionSpecs.all { !it.hasCustomGrain }) {
            "Found custom granularity in partition time dimension specs $timeDimensionSpecs, but time partitions " +
                "can only use standard granularities as they are based on engine date/time types!"
        }
        return timeDimensionSpecs.sortedWith(
            compareBy({ it.baseGranularitySortKey }, { it.entityLinks.size }),
        ).first()
    }

    private fun <T> List<T>.distinctPreservingOrder(): List<T> = LinkedHashSet(this).toList()
}
