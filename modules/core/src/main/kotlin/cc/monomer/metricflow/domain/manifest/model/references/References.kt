package cc.monomer.metricflow.domain.manifest.model.references

import kotlinx.serialization.Serializable

/**
 * Reference types — single-field, immutable, ID-like identifiers for elements
 * (measures, dimensions, entities, metrics) and semantic models.
 *
 * Port of `metricflow_semantic_interfaces/references.py`.
 *
 * In Python these are dataclass subclasses of `ElementReference`. In Kotlin we
 * use `@JvmInline value class`es for zero-allocation runtime cost plus type
 * safety; the inheritance chain is restated via `sealed interface` markers
 * ([ElementReference] and [LinkableElementReference]).
 *
 * Serialization: kotlinx-serialization automatically encodes a value class
 * as its single field. JSON-side these references appear either as bare
 * strings (free-standing) or as objects with one key (when contained in a
 * larger record).
 */

/** Used when we need to refer to a dimension, measure, entity, but other attributes are unknown. */
sealed interface ElementReference {
    val elementName: String
}

/** Element reference that participates in joins (entity / dimension). */
sealed interface LinkableElementReference : ElementReference

/** Marker for references into the model definition (not element-shaped). */
sealed interface ModelReference

/** Used when we need to refer to a measure. Measures aren't linkable. */
@JvmInline
@Serializable
value class MeasureReference(override val elementName: String) :
    ElementReference, Comparable<MeasureReference> {
    override fun compareTo(other: MeasureReference): Int = elementName.compareTo(other.elementName)
}

/** Used when we need to refer to a dimension. */
@JvmInline
@Serializable
value class DimensionReference(override val elementName: String) :
    LinkableElementReference, Comparable<DimensionReference> {
    /** Adapter: return this name interpreted as a time dimension reference. */
    val timeDimensionReference: TimeDimensionReference get() = TimeDimensionReference(elementName)
    override fun compareTo(other: DimensionReference): Int = elementName.compareTo(other.elementName)
}

/** A dimension whose type is TIME. */
@JvmInline
@Serializable
value class TimeDimensionReference(override val elementName: String) :
    LinkableElementReference, Comparable<TimeDimensionReference> {
    val dimensionReference: DimensionReference get() = DimensionReference(elementName)
    override fun compareTo(other: TimeDimensionReference): Int = elementName.compareTo(other.elementName)
}

/** Used when we need to refer to an entity. */
@JvmInline
@Serializable
value class EntityReference(override val elementName: String) :
    LinkableElementReference, Comparable<EntityReference> {
    override fun compareTo(other: EntityReference): Int = elementName.compareTo(other.elementName)
}

/** Used when we need to refer to a metric. */
@JvmInline
@Serializable
value class MetricReference(override val elementName: String) :
    ElementReference, Comparable<MetricReference> {
    override fun compareTo(other: MetricReference): Int = elementName.compareTo(other.elementName)
}

/**
 * Represents a metric used as a group-by item. Distinct from [MetricReference]
 * because it participates in the linkable-element hierarchy.
 */
@JvmInline
@Serializable
value class GroupByMetricReference(override val elementName: String) :
    LinkableElementReference

/** A reference to a semantic model definition in the model. */
@JvmInline
@Serializable
value class SemanticModelReference(val semanticModelName: String) :
    ModelReference, Comparable<SemanticModelReference> {
    override fun compareTo(other: SemanticModelReference): Int =
        semanticModelName.compareTo(other.semanticModelName)
}

/** A reference to an element definition in a semantic model definition in the model. */
@Serializable
data class SemanticModelElementReference(
    val semanticModelName: String,
    val elementName: String,
) : ModelReference, Comparable<SemanticModelElementReference> {

    val semanticModelReference: SemanticModelReference
        get() = SemanticModelReference(semanticModelName)

    /** Returns true iff this reference is from the same semantic model as the supplied reference. */
    fun isFrom(ref: SemanticModelReference): Boolean =
        semanticModelName == ref.semanticModelName

    override fun compareTo(other: SemanticModelElementReference): Int {
        val byModel = semanticModelName.compareTo(other.semanticModelName)
        return if (byModel != 0) byModel else elementName.compareTo(other.elementName)
    }

    companion object {
        fun createFromReferences(
            semanticModelReference: SemanticModelReference,
            elementReference: ElementReference,
        ): SemanticModelElementReference = SemanticModelElementReference(
            semanticModelName = semanticModelReference.semanticModelName,
            elementName = elementReference.elementName,
        )
    }
}

/** A reference to a metric definition in the model. */
@JvmInline
@Serializable
value class MetricModelReference(val metricName: String) :
    ModelReference, Comparable<MetricModelReference> {
    override fun compareTo(other: MetricModelReference): Int =
        metricName.compareTo(other.metricName)
}
