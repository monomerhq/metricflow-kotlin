package cc.monomer.metricflow.domain.manifest.model

import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.element.Measure
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.LinkableElementReference
import cc.monomer.metricflow.domain.manifest.model.references.MeasureReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import kotlinx.serialization.Serializable

/**
 * Default values for a semantic model — currently only the aggregation-time dimension.
 *
 * Port of `metricflow_semantic_interfaces/implementations/semantic_model.py::PydanticSemanticModelDefaults`.
 */
@Serializable
data class SemanticModelDefaults(
    val aggTimeDimension: String? = null,
)

/**
 * A semantic model: one source-of-truth dataset with measures, dimensions, and entities.
 *
 * Port of `metricflow_semantic_interfaces/implementations/semantic_model.py::PydanticSemanticModel`.
 *
 * Holds the join graph (entities), the aggregatable columns (measures), and the grouping
 * columns (dimensions) along with the underlying physical relation ([NodeRelation]).
 */
@Serializable
data class SemanticModel(
    val name: String,
    val defaults: SemanticModelDefaults? = null,
    val description: String? = null,
    val nodeRelation: NodeRelation,
    val primaryEntity: String? = null,
    val entities: List<Entity> = emptyList(),
    val measures: List<Measure> = emptyList(),
    val dimensions: List<Dimension> = emptyList(),
    val label: String? = null,
    val metadata: Metadata? = null,
    val config: SemanticLayerElementConfig? = null,
) {

    val entityReferences: List<LinkableElementReference>
        get() = entities.map { it.reference }

    val dimensionReferences: List<LinkableElementReference>
        get() = dimensions.map { it.reference }

    val measureReferences: List<MeasureReference>
        get() = measures.map { it.reference }

    /** True iff any dimension has validity params (i.e., this is an SCD Type II model). */
    val hasValidityDimensions: Boolean
        get() = dimensions.any { it.validityParams != null }

    /** The single dimension marking the start of an SCD validity window, if any. */
    val validityStartDimension: Dimension?
        get() {
            val matches = dimensions.filter { it.validityParams?.isStart == true }
            if (matches.isEmpty()) return null
            check(matches.size == 1) {
                "Found more than one validity start dimension. This should have been blocked in validation!"
            }
            return matches.single()
        }

    /** The single dimension marking the end of an SCD validity window, if any. */
    val validityEndDimension: Dimension?
        get() {
            val matches = dimensions.filter { it.validityParams?.isEnd == true }
            if (matches.isEmpty()) return null
            check(matches.size == 1) {
                "Found more than one validity end dimension. This should have been blocked in validation!"
            }
            return matches.single()
        }

    /** All partition-tagged dimensions on this model. */
    val partitions: List<Dimension>
        get() = dimensions.filter { it.isPartition }

    /** Convenience accessor — exactly one partition (or null), throws if more than one. */
    val partition: Dimension?
        get() {
            val p = partitions
            if (p.isEmpty()) return null
            if (p.size > 1) throw IllegalStateException("too many partitions for semantic_model $name")
            return p.single()
        }

    /** A [SemanticModelReference] pointing back to this model. */
    val reference: SemanticModelReference get() = SemanticModelReference(name)

    /** The primary entity as a typed reference, or null if not set. */
    val primaryEntityReference: EntityReference?
        get() = primaryEntity?.let { EntityReference(it) }

    fun getMeasure(measureReference: MeasureReference): Measure =
        measures.firstOrNull { it.reference == measureReference }
            ?: throw IllegalArgumentException(
                "No measure with name (${measureReference.elementName}) in semantic_model with name ($name)",
            )

    fun getDimension(dimensionReference: DimensionReference): Dimension =
        dimensions.firstOrNull { it.reference == dimensionReference }
            ?: throw IllegalArgumentException(
                "No dimension with name ($dimensionReference) in semantic_model with name ($name)",
            )

    fun getEntity(entityReference: LinkableElementReference): Entity =
        entities.firstOrNull { it.reference == entityReference }
            ?: throw IllegalArgumentException(
                "No entity with name ($entityReference) in semantic_model with name ($name)",
            )

    private fun defaultAggTimeDimension(): String? = defaults?.aggTimeDimension

    /**
     * Returns the aggregation-time dimension for a simple metric whose aggregation is sourced
     * from this semantic model. Falls back to the model's default.
     */
    fun checkedAggTimeDimensionForSimpleMetric(metric: Metric): TimeDimensionReference {
        check(metric.type == MetricType.SIMPLE) { "Only simple metrics can have an agg time dimension." }
        val params = metric.typeParams.metricAggregationParams
        checkNotNull(params) { "Simple metrics must have metric_aggregation_params." }
        check(params.semanticModel == name) {
            "Cannot retrieve the agg time dimension for a metric from a different model than the one that " +
                "the metric belongs to. Metric `${metric.name}` belongs to model `${params.semanticModel}`, " +
                "but we requested the agg time dimension from model `$name`."
        }
        val explicit = params.aggTimeDimension
        val resolved = explicit ?: defaultAggTimeDimension()
        checkNotNull(resolved) {
            "Aggregation time dimension for metric ${metric.name} is not set! This should either be set " +
                "directly on the metric specification in the model, or else defaulted to the time dimension " +
                "in the data source containing the metric."
        }
        return TimeDimensionReference(resolved)
    }

    /** Returns the aggregation-time dimension for a measure. Falls back to the model's default. */
    fun checkedAggTimeDimensionForMeasure(measureReference: MeasureReference): TimeDimensionReference {
        val measure = getMeasure(measureReference)
        val resolved = measure.aggTimeDimension ?: defaultAggTimeDimension()
        checkNotNull(resolved) {
            "Aggregation time dimension for measure ${measure.name} is not set! This should either be set " +
                "directly on the measure specification in the model, or else defaulted to the primary time " +
                "dimension in the data source containing the measure."
        }
        return TimeDimensionReference(resolved)
    }
}
