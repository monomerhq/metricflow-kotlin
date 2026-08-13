package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.spec.InstanceSpec

/**
 * Match specs against an explicit allow-list of [listedSpecs].
 *
 * Port of `metricflow_semantics.specs.patterns.match_list_pattern.MatchListSpecPattern`.
 *
 * Useful for filtering possible group-by-items to those valid for a query.
 */
data class MatchListSpecPattern(val listedSpecs: List<InstanceSpec>) : SpecPattern {

    private val listedSet: Set<InstanceSpec> by lazy { listedSpecs.toSet() }

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<InstanceSpec> =
        candidateSpecs.filter { it in listedSet }

    companion object {
        fun create(listedSpecs: Iterable<InstanceSpec>): MatchListSpecPattern =
            MatchListSpecPattern(listedSpecs.toList())
    }
}
