package cc.monomer.metricflow.domain.dataflow.instance

import cc.monomer.metricflow.domain.spec.InstanceSpecSet

/**
 * A set that includes all instance variants — port of `metricflow_semantics.instances.InstanceSet`.
 *
 * Generally used to represent the data flowing between nodes in the dataflow plan. Each node's
 * output is described by an [InstanceSet] (which columns are present, what specs they correspond
 * to, what semantic models / aggregation states they originated from).
 *
 * The fields preserve Python iteration order. Use [specSet] to project the contents into the
 * pure-spec view used elsewhere (`InstanceSpecSet`).
 */
data class InstanceSet(
    val simpleMetricInputInstances: List<SimpleMetricInputInstance>,
    val dimensionInstances: List<DimensionInstance>,
    val timeDimensionInstances: List<TimeDimensionInstance>,
    val entityInstances: List<EntityInstance>,
    val groupByMetricInstances: List<GroupByMetricInstance>,
    val metricInstances: List<MetricInstance>,
    val metadataInstances: List<MetadataInstance>,
) {

    /** Apply a transform — port of `InstanceSet.transform`. */
    fun <R> transform(transformer: InstanceSetTransform<R>): R = transformer.transform(this)

    /** Project this set into the spec-only view. Port of `InstanceSet.spec_set`. */
    val specSet: InstanceSpecSet
        get() = InstanceSpecSet(
            metricSpecs = metricInstances.map { it.spec },
            simpleMetricInputSpecs = simpleMetricInputInstances.map { it.spec },
            dimensionSpecs = dimensionInstances.map { it.spec },
            entitySpecs = entityInstances.map { it.spec },
            timeDimensionSpecs = timeDimensionInstances.map { it.spec },
            groupByMetricSpecs = groupByMetricInstances.map { it.spec },
            metadataSpecs = metadataInstances.map { it.spec },
        )

    /** Flatten all instance variants in iteration order. Port of `InstanceSet.as_tuple`. */
    val asList: List<MdoInstance>
        get() = simpleMetricInputInstances +
            dimensionInstances +
            timeDimensionInstances +
            entityInstances +
            groupByMetricInstances +
            metricInstances +
            metadataInstances

    /** All linkable variants in order — port of `InstanceSet.linkable_instances`. */
    val linkableInstances: List<LinkableInstance>
        get() = dimensionInstances + timeDimensionInstances + entityInstances + groupByMetricInstances

    /** Return a copy without simple-metric inputs. Port of `without_simple_metric_inputs`. */
    fun withoutSimpleMetricInputs(): InstanceSet = copy(simpleMetricInputInstances = emptyList())

    companion object {
        /** Empty set — convenience constant. */
        val EMPTY: InstanceSet = InstanceSet(
            simpleMetricInputInstances = emptyList(),
            dimensionInstances = emptyList(),
            timeDimensionInstances = emptyList(),
            entityInstances = emptyList(),
            groupByMetricInstances = emptyList(),
            metricInstances = emptyList(),
            metadataInstances = emptyList(),
        )

        /**
         * Combine all instances across the supplied sets, de-duping by spec. Port of
         * `InstanceSet.merge`. The first-seen instance for any given spec wins.
         */
        fun merge(instanceSets: List<InstanceSet>): InstanceSet {
            val simpleMetricInputs = LinkedHashMap<Any, SimpleMetricInputInstance>()
            val dimensions = LinkedHashMap<Any, DimensionInstance>()
            val timeDimensions = LinkedHashMap<Any, TimeDimensionInstance>()
            val entities = LinkedHashMap<Any, EntityInstance>()
            val groupByMetrics = LinkedHashMap<Any, GroupByMetricInstance>()
            val metrics = LinkedHashMap<Any, MetricInstance>()
            val metadata = LinkedHashMap<Any, MetadataInstance>()

            for (set in instanceSets) {
                for (x in set.simpleMetricInputInstances) simpleMetricInputs.putIfAbsent(x.spec, x)
                for (x in set.dimensionInstances) dimensions.putIfAbsent(x.spec, x)
                for (x in set.timeDimensionInstances) timeDimensions.putIfAbsent(x.spec, x)
                for (x in set.entityInstances) entities.putIfAbsent(x.spec, x)
                for (x in set.groupByMetricInstances) groupByMetrics.putIfAbsent(x.spec, x)
                for (x in set.metricInstances) metrics.putIfAbsent(x.spec, x)
                for (x in set.metadataInstances) metadata.putIfAbsent(x.spec, x)
            }

            return InstanceSet(
                simpleMetricInputInstances = simpleMetricInputs.values.toList(),
                dimensionInstances = dimensions.values.toList(),
                timeDimensionInstances = timeDimensions.values.toList(),
                entityInstances = entities.values.toList(),
                groupByMetricInstances = groupByMetrics.values.toList(),
                metricInstances = metrics.values.toList(),
                metadataInstances = metadata.values.toList(),
            )
        }

        /**
         * Group a sequence of [MdoInstance]s into an [InstanceSet] by variant type.
         * Port of Python `group_instances_by_type`.
         */
        fun groupInstancesByType(instances: Iterable<MdoInstance>): InstanceSet {
            val grouper = GroupInstanceByTypeVisitor()
            for (instance in instances) instance.accept(grouper)
            return InstanceSet(
                metricInstances = grouper.metricInstances.toList(),
                simpleMetricInputInstances = grouper.simpleMetricInputInstances.toList(),
                dimensionInstances = grouper.dimensionInstances.toList(),
                entityInstances = grouper.entityInstances.toList(),
                timeDimensionInstances = grouper.timeDimensionInstances.toList(),
                groupByMetricInstances = grouper.groupByMetricInstances.toList(),
                metadataInstances = grouper.metadataInstances.toList(),
            )
        }
    }
}

/**
 * Transform an [InstanceSet] into something else. Port of
 * `metricflow_semantics.instances.InstanceSetTransform`.
 *
 * Using a dedicated interface (rather than ad-hoc lambdas) makes every instance-set
 * transformation locatable via "Find Usages" — important when new instance variants are added.
 */
interface InstanceSetTransform<R> {
    fun transform(instanceSet: InstanceSet): R
}

/** Internal visitor used by [InstanceSet.groupInstancesByType]. */
private class GroupInstanceByTypeVisitor : InstanceVisitor<Unit> {
    val simpleMetricInputInstances = mutableListOf<SimpleMetricInputInstance>()
    val dimensionInstances = mutableListOf<DimensionInstance>()
    val timeDimensionInstances = mutableListOf<TimeDimensionInstance>()
    val entityInstances = mutableListOf<EntityInstance>()
    val groupByMetricInstances = mutableListOf<GroupByMetricInstance>()
    val metricInstances = mutableListOf<MetricInstance>()
    val metadataInstances = mutableListOf<MetadataInstance>()

    override fun visitSimpleMetricInputInstance(instance: SimpleMetricInputInstance) {
        simpleMetricInputInstances.add(instance)
    }

    override fun visitDimensionInstance(instance: DimensionInstance) {
        dimensionInstances.add(instance)
    }

    override fun visitTimeDimensionInstance(instance: TimeDimensionInstance) {
        timeDimensionInstances.add(instance)
    }

    override fun visitEntityInstance(instance: EntityInstance) {
        entityInstances.add(instance)
    }

    override fun visitGroupByMetricInstance(instance: GroupByMetricInstance) {
        groupByMetricInstances.add(instance)
    }

    override fun visitMetricInstance(instance: MetricInstance) {
        metricInstances.add(instance)
    }

    override fun visitMetadataInstance(instance: MetadataInstance) {
        metadataInstances.add(instance)
    }
}
