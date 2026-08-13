package cc.monomer.metricflow.domain.semantic_graph.metric_input

import cc.monomer.metricflow.domain.spec.NonAdditiveDimensionSpec

/**
 * Spec factories that bridge [SimpleMetricInput] (a `:domain:semantic-graph`
 * type) and `:domain:spec`'s [NonAdditiveDimensionSpec].
 *
 * The matching Python class method lives on `NonAdditiveDimensionSpec`:
 *
 *     @staticmethod
 *     def create_from_simple_metric_input(simple_metric_input: SimpleMetricInput) -> Optional[NonAdditiveDimensionSpec]
 *
 * In Kotlin we cannot host this static factory on [NonAdditiveDimensionSpec]
 * itself: `:domain:spec` is a strictly lower layer than `:domain:semantic-graph`
 * and may not see [SimpleMetricInput]. The factory therefore lives next to its
 * input type and the W7c deferral note on [NonAdditiveDimensionSpec] points
 * downstream code here.
 */
object NonAdditiveDimensionSpecFactory {

    /**
     * Build a [NonAdditiveDimensionSpec] from a [SimpleMetricInput].
     *
     * Returns `null` when the input declares no non-additive dimension —
     * matches Python's `Optional[NonAdditiveDimensionSpec]` return type.
     *
     * Mirrors Python's:
     * ```
     * NonAdditiveDimensionSpec(
     *   name=simple_metric_input.non_additive_dimension.name,
     *   window_choice=...,
     *   window_groupings=tuple(sorted(...)),
     * )
     * ```
     */
    fun createFromSimpleMetricInput(simpleMetricInput: SimpleMetricInput): NonAdditiveDimensionSpec? {
        val nonAdditive = simpleMetricInput.nonAdditiveDimension ?: return null
        return NonAdditiveDimensionSpec(
            name = nonAdditive.name,
            windowChoice = nonAdditive.windowChoice,
            windowGroupings = nonAdditive.windowGroupings.sorted(),
        )
    }
}
