package cc.monomer.metricflow.domain.dataflow.instance

import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.spec.AggregationState
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.GroupByMetricSpec
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.MetadataSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * An instance of a metric definition object — port of `metricflow_semantics.instances.MdoInstance`.
 *
 * An instance is different from the metric definition object in that it correlates to columns in
 * a data set and can be in different states. For example, a simple-metric-input instance can be
 * aggregated, and a time-dimension instance can be at a different granularity than the column it
 * originally read from.
 *
 * Each variant carries the [InstanceSpec] subtype that describes it ([spec]) and the [ColumnAssociation]
 * tuple that names the columns in the underlying SQL dataset.
 */
sealed interface MdoInstance {
    /** The columns associated with this instance. Port of `MdoInstance.associated_columns`. */
    val associatedColumns: List<ColumnAssociation>

    /** The spec that describes this instance. Port of `MdoInstance.spec`. */
    val spec: InstanceSpec

    /**
     * Helper that returns the single associated column. Port of `MdoInstance.associated_column`.
     * Fails if there are 0 or >1 associated columns — mirrors the Python `assert`.
     */
    val associatedColumn: ColumnAssociation
        get() {
            check(associatedColumns.size == 1) {
                "Expected exactly one column for ${this::class.simpleName}, but got $associatedColumns"
            }
            return associatedColumns[0]
        }

    /** Dispatch to a visitor — port of `MdoInstance.accept`. */
    fun <R> accept(visitor: InstanceVisitor<R>): R
}

/**
 * A linkable instance — port of `metricflow_semantics.instances.LinkableInstance`. The spec is
 * one of the linkable types (`DimensionSpec`, `TimeDimensionSpec`, `EntitySpec`, `GroupByMetricSpec`),
 * which means it can have entity links.
 */
sealed interface LinkableInstance : MdoInstance {
    /** Prepend an entity link to the underlying spec. Port of `with_entity_prefix`. */
    fun withEntityPrefix(
        entityPrefix: EntityReference,
        columnAssociationResolver: ColumnAssociationResolver,
    ): MdoInstance
}

/**
 * An instance derived from a semantic-model element. Port of
 * `metricflow_semantics.instances.SemanticModelElementInstance` (mix-in trait — Kotlin models it
 * as an interface so the concrete instance classes can provide [definedFrom]).
 */
sealed interface SemanticModelElementInstance {
    /** The semantic-model element(s) this instance is derived from. */
    val definedFrom: List<SemanticModelElementReference>

    /**
     * The reference to the origin semantic model. Port of `origin_semantic_model_reference`.
     * Requires exactly one entry in [definedFrom].
     */
    val originSemanticModelReference: SemanticModelElementReference
        get() {
            check(definedFrom.size == 1) {
                "SemanticModelElementInstances should have exactly one entry in `defined_from`, " +
                    "got ${definedFrom.size}: $definedFrom"
            }
            return definedFrom[0]
        }
}

// -------- Concrete instance variants --------

/** Port of `SimpleMetricInputInstance`. Carries an [AggregationState] that advances downstream. */
data class SimpleMetricInputInstance(
    override val associatedColumns: List<ColumnAssociation>,
    override val definedFrom: List<SemanticModelElementReference>,
    override val spec: SimpleMetricInputSpec,
    val aggregationState: AggregationState,
) : MdoInstance, SemanticModelElementInstance {

    override fun <R> accept(visitor: InstanceVisitor<R>): R = visitor.visitSimpleMetricInputInstance(this)

    /** Replace the spec, resolving fresh column associations. */
    fun withNewSpec(
        newSpec: SimpleMetricInputSpec,
        columnAssociationResolver: ColumnAssociationResolver,
    ): SimpleMetricInputInstance = SimpleMetricInputInstance(
        associatedColumns = listOf(columnAssociationResolver.resolveSpec(newSpec)),
        definedFrom = definedFrom,
        spec = newSpec,
        aggregationState = aggregationState,
    )
}

/** Port of `DimensionInstance`. */
data class DimensionInstance(
    override val associatedColumns: List<ColumnAssociation>,
    override val definedFrom: List<SemanticModelElementReference>,
    override val spec: DimensionSpec,
) : LinkableInstance, SemanticModelElementInstance {

    override fun <R> accept(visitor: InstanceVisitor<R>): R = visitor.visitDimensionInstance(this)

    override fun withEntityPrefix(
        entityPrefix: EntityReference,
        columnAssociationResolver: ColumnAssociationResolver,
    ): DimensionInstance {
        val transformed = spec.withEntityPrefix(entityPrefix)
        return DimensionInstance(
            associatedColumns = listOf(columnAssociationResolver.resolveSpec(transformed)),
            definedFrom = definedFrom,
            spec = transformed,
        )
    }

    /** Replace the spec, resolving fresh column associations. */
    fun withNewSpec(
        newSpec: DimensionSpec,
        columnAssociationResolver: ColumnAssociationResolver,
    ): DimensionInstance = DimensionInstance(
        associatedColumns = listOf(columnAssociationResolver.resolveSpec(newSpec)),
        definedFrom = definedFrom,
        spec = newSpec,
    )
}

/** Port of `TimeDimensionInstance`. */
data class TimeDimensionInstance(
    override val associatedColumns: List<ColumnAssociation>,
    override val definedFrom: List<SemanticModelElementReference>,
    override val spec: TimeDimensionSpec,
) : LinkableInstance, SemanticModelElementInstance {

    override fun <R> accept(visitor: InstanceVisitor<R>): R = visitor.visitTimeDimensionInstance(this)

    override fun withEntityPrefix(
        entityPrefix: EntityReference,
        columnAssociationResolver: ColumnAssociationResolver,
    ): TimeDimensionInstance {
        val transformed = spec.withEntityPrefix(entityPrefix)
        return withNewSpec(transformed, columnAssociationResolver)
    }

    /** Replace the `definedFrom` field while keeping spec/columns. Port of `with_new_defined_from`. */
    fun withNewDefinedFrom(definedFrom: List<SemanticModelElementReference>): TimeDimensionInstance =
        TimeDimensionInstance(
            associatedColumns = associatedColumns,
            definedFrom = definedFrom,
            spec = spec,
        )

    /** Replace the spec, resolving fresh column associations. */
    fun withNewSpec(
        newSpec: TimeDimensionSpec,
        columnAssociationResolver: ColumnAssociationResolver,
    ): TimeDimensionInstance = TimeDimensionInstance(
        associatedColumns = listOf(columnAssociationResolver.resolveSpec(newSpec)),
        definedFrom = definedFrom,
        spec = newSpec,
    )
}

/** Port of `EntityInstance`. */
data class EntityInstance(
    override val associatedColumns: List<ColumnAssociation>,
    override val definedFrom: List<SemanticModelElementReference>,
    override val spec: EntitySpec,
) : LinkableInstance, SemanticModelElementInstance {

    override fun <R> accept(visitor: InstanceVisitor<R>): R = visitor.visitEntityInstance(this)

    override fun withEntityPrefix(
        entityPrefix: EntityReference,
        columnAssociationResolver: ColumnAssociationResolver,
    ): EntityInstance {
        val transformed = spec.withEntityPrefix(entityPrefix)
        return EntityInstance(
            associatedColumns = listOf(columnAssociationResolver.resolveSpec(transformed)),
            definedFrom = definedFrom,
            spec = transformed,
        )
    }

    /** Replace the spec, resolving fresh column associations. */
    fun withNewSpec(
        newSpec: EntitySpec,
        columnAssociationResolver: ColumnAssociationResolver,
    ): EntityInstance = EntityInstance(
        associatedColumns = listOf(columnAssociationResolver.resolveSpec(newSpec)),
        definedFrom = definedFrom,
        spec = newSpec,
    )
}

/** Port of `GroupByMetricInstance`. */
data class GroupByMetricInstance(
    override val associatedColumns: List<ColumnAssociation>,
    override val spec: GroupByMetricSpec,
    val definedFrom: MetricModelReference,
) : LinkableInstance {

    override fun <R> accept(visitor: InstanceVisitor<R>): R = visitor.visitGroupByMetricInstance(this)

    override fun withEntityPrefix(
        entityPrefix: EntityReference,
        columnAssociationResolver: ColumnAssociationResolver,
    ): GroupByMetricInstance {
        val transformed = spec.withEntityPrefix(entityPrefix)
        return GroupByMetricInstance(
            associatedColumns = listOf(columnAssociationResolver.resolveSpec(transformed)),
            spec = transformed,
            definedFrom = definedFrom,
        )
    }

    /** Replace the spec, resolving fresh column associations. */
    fun withNewSpec(
        newSpec: GroupByMetricSpec,
        columnAssociationResolver: ColumnAssociationResolver,
    ): GroupByMetricInstance = GroupByMetricInstance(
        associatedColumns = listOf(columnAssociationResolver.resolveSpec(newSpec)),
        spec = newSpec,
        definedFrom = definedFrom,
    )
}

/** Port of `MetricInstance`. */
data class MetricInstance(
    override val associatedColumns: List<ColumnAssociation>,
    override val spec: MetricSpec,
    val definedFrom: MetricModelReference,
) : MdoInstance {

    override fun <R> accept(visitor: InstanceVisitor<R>): R = visitor.visitMetricInstance(this)

    /** Replace the spec, resolving fresh column associations. */
    fun withNewSpec(
        newSpec: MetricSpec,
        columnAssociationResolver: ColumnAssociationResolver,
    ): MetricInstance = MetricInstance(
        associatedColumns = listOf(columnAssociationResolver.resolveSpec(newSpec)),
        spec = newSpec,
        definedFrom = definedFrom,
    )
}

/** Port of `MetadataInstance`. */
data class MetadataInstance(
    override val associatedColumns: List<ColumnAssociation>,
    override val spec: MetadataSpec,
) : MdoInstance {

    override fun <R> accept(visitor: InstanceVisitor<R>): R = visitor.visitMetadataInstance(this)

    /** Replace the spec, resolving fresh column associations. */
    fun withNewSpec(
        newSpec: MetadataSpec,
        columnAssociationResolver: ColumnAssociationResolver,
    ): MetadataInstance = MetadataInstance(
        associatedColumns = listOf(columnAssociationResolver.resolveSpec(newSpec)),
        spec = newSpec,
    )
}
