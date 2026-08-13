package cc.monomer.metricflow.domain.metric_evaluation.plan

import cc.monomer.metricflow.domain.plan_conversion.node_processor.PredicatePushdownState
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableSpecSet
import cc.monomer.metricflow.domain.spec.MetricSpec

/**
 * Properties that determine whether metric query elements can be composed into
 * a single underlying SQL query.
 *
 * Port of `metricflow.metric_evaluation.plan.query_element.MetricQueryPropertySet`.
 *
 * Two query elements with the same property set can be grouped into one query
 * (e.g. one `SimpleMetricsQueryNode` that computes both metrics); different
 * property sets force separate queries because they need different `WHERE`
 * clauses or different group-by columns.
 */
data class MetricQueryPropertySet(
    val groupByItemSpecs: List<LinkableInstanceSpec>,
    val predicatePushdownState: PredicatePushdownState,
) {

    /**
     * Materialised view of [groupByItemSpecs] as a [LinkableSpecSet]. Cached at
     * the type level — call sites that compute the spec-set repeatedly avoid
     * the rebuild.
     */
    val groupByItemSpecSet: LinkableSpecSet by lazy {
        LinkableSpecSet.createFromSpecs(groupByItemSpecs)
    }

    companion object {
        /** Factory mirroring Python `MetricQueryPropertySet.create`. */
        fun create(
            groupByItemSpecs: Iterable<LinkableInstanceSpec>,
            predicatePushdownState: PredicatePushdownState,
        ): MetricQueryPropertySet = MetricQueryPropertySet(
            groupByItemSpecs = groupByItemSpecs.toList(),
            predicatePushdownState = predicatePushdownState,
        )
    }
}

/**
 * A composable element that is used to build a query for metrics.
 *
 * Port of `metricflow.metric_evaluation.plan.query_element.MetricQueryElement`.
 *
 * Two `MetricQueryElement`s with the same [queryProperties] can be combined
 * into a single underlying SQL query. The planner uses this to decide which
 * inputs of a derived metric can share a base query node.
 */
data class MetricQueryElement(
    val metricSpec: MetricSpec,
    val queryProperties: MetricQueryPropertySet,
) {

    /** Group-by specs required to compute this element. */
    val groupByItemSpecs: List<LinkableInstanceSpec> get() = queryProperties.groupByItemSpecs

    /** Filter-pushdown state inherited by this element. */
    val predicatePushdownState: PredicatePushdownState get() = queryProperties.predicatePushdownState

    /** Element name of the underlying metric — convenience accessor. */
    val metricName: String get() = metricSpec.elementName

    companion object {
        /** Factory mirroring Python `MetricQueryElement.create`. */
        fun create(
            metricSpec: MetricSpec,
            groupByItemSpecs: Iterable<LinkableInstanceSpec>,
            predicatePushdownState: PredicatePushdownState,
        ): MetricQueryElement = MetricQueryElement(
            metricSpec = metricSpec,
            queryProperties = MetricQueryPropertySet.create(
                groupByItemSpecs = groupByItemSpecs,
                predicatePushdownState = predicatePushdownState,
            ),
        )
    }
}

/**
 * Read-only lookup for the [MetricQueryElement]s collected during metric
 * dependency traversal.
 *
 * Port of `metricflow.metric_evaluation.plan.query_element.MetricQueryElementLookup`.
 */
interface MetricQueryElementLookup {
    /** All query elements collected so far, in insertion order. */
    val queryElements: Set<MetricQueryElement>

    /** Return the direct dependencies for [queryElement]. */
    fun getInputQueryElements(queryElement: MetricQueryElement): List<MetricQueryElement>

    /** Map of every query element to its direct dependencies. */
    val queryElementToInputElements: Map<MetricQueryElement, List<MetricQueryElement>>
}

/**
 * Mutable collector for [MetricQueryElement] dependencies, used while walking
 * the metric definition graph.
 *
 * Port of `metricflow.metric_evaluation.plan.query_element.MetricQueryElementCollector`.
 */
class MetricQueryElementCollector : MetricQueryElementLookup {

    private val backingMap: LinkedHashMap<MetricQueryElement, List<MetricQueryElement>> = LinkedHashMap()

    /** Add a query element together with the elements it directly depends on. */
    fun addQueryElement(
        queryElement: MetricQueryElement,
        inputQueryElements: Iterable<MetricQueryElement>?,
    ) {
        if (queryElement in backingMap) {
            throw IllegalStateException("Query element already added: $queryElement")
        }
        backingMap[queryElement] = inputQueryElements?.toList() ?: emptyList()
    }

    override val queryElements: Set<MetricQueryElement>
        get() = backingMap.keys

    override fun getInputQueryElements(queryElement: MetricQueryElement): List<MetricQueryElement> {
        val inputs = backingMap[queryElement]
            ?: throw IllegalArgumentException(
                "Unknown query element: $queryElement; known: ${backingMap.keys}",
            )
        return inputs
    }

    override val queryElementToInputElements: Map<MetricQueryElement, List<MetricQueryElement>>
        get() = backingMap
}
