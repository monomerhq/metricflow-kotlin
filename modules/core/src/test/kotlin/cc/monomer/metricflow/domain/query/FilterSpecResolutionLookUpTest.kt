package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.parameterset.DimensionCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssueSet
import cc.monomer.metricflow.domain.query.resolution.CallParameterSet
import cc.monomer.metricflow.domain.query.resolution.FilterSpecResolution
import cc.monomer.metricflow.domain.query.resolution.FilterSpecResolutionLookUp
import cc.monomer.metricflow.domain.query.resolution.ResolvedSpecLookUpKey
import cc.monomer.metricflow.domain.query.resolution.WhereFilterLocation
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet
import cc.monomer.metricflow.domain.spec.pattern.MetricSpecPattern
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterSpecResolutionLookUpTest {

    private val callParameterSet = CallParameterSet.Dimension(
        DimensionCallParameterSet(
            entityPath = listOf(EntityReference("listing")),
            dimensionReference = DimensionReference("country"),
            descending = null,
        ),
    )

    private val resolvedSpecKey = ResolvedSpecLookUpKey(
        filterLocation = WhereFilterLocation.forQuery(listOf(MetricReference("bookings"))),
        callParameterSet = callParameterSet,
    )

    @Test
    fun `EMPTY has no errors and no resolutions`() {
        assertFalse(FilterSpecResolutionLookUp.EMPTY.hasErrors)
        assertFalse(FilterSpecResolutionLookUp.EMPTY.hasIssues)
        assertFalse(FilterSpecResolutionLookUp.EMPTY.specResolutionExists(resolvedSpecKey))
    }

    @Test
    fun `getSpecResolutions filters by key`() {
        val resolution = FilterSpecResolution(
            lookupKey = resolvedSpecKey,
            whereFilterIntersection = WhereFilterIntersection(emptyList()),
            resolvedGroupByItemSet = GroupByItemSet.EMPTY,
            specPattern = MetricSpecPattern(MetricReference("bookings"), null),
            issueSet = MetricFlowQueryResolutionIssueSet.EMPTY,
            filterLocationPath = MetricFlowQueryResolutionPath.EMPTY,
            objectBuilderStr = "Dimension('listing__country')",
        )
        val lookup = FilterSpecResolutionLookUp(
            specResolutions = listOf(resolution),
            nonParsableResolutions = emptyList(),
        )
        assertTrue(lookup.specResolutionExists(resolvedSpecKey))
        assertEquals(1, lookup.getSpecResolutions(resolvedSpecKey).size)
    }

    @Test
    fun `merge concatenates resolutions`() {
        val a = FilterSpecResolutionLookUp(
            specResolutions = listOf(
                FilterSpecResolution(
                    lookupKey = resolvedSpecKey,
                    whereFilterIntersection = WhereFilterIntersection(emptyList()),
                    resolvedGroupByItemSet = GroupByItemSet.EMPTY,
                    specPattern = MetricSpecPattern(MetricReference("bookings"), null),
                    issueSet = MetricFlowQueryResolutionIssueSet.EMPTY,
                    filterLocationPath = MetricFlowQueryResolutionPath.EMPTY,
                    objectBuilderStr = "x",
                ),
            ),
            nonParsableResolutions = emptyList(),
        )
        val merged = a.merge(FilterSpecResolutionLookUp.EMPTY)
        assertEquals(1, merged.specResolutions.size)
    }

    @Test
    fun `checkedResolvedSpec throws when key is missing`() {
        assertFailsWith<RuntimeException> {
            FilterSpecResolutionLookUp.EMPTY.checkedResolvedSpec(resolvedSpecKey)
        }
    }

    @Test
    fun `FilterSpecResolution enforces at most one resolved spec`() {
        // Spec with no annotated specs (size 0) is OK.
        FilterSpecResolution(
            lookupKey = resolvedSpecKey,
            whereFilterIntersection = WhereFilterIntersection(emptyList()),
            resolvedGroupByItemSet = GroupByItemSet.EMPTY,
            specPattern = MetricSpecPattern(MetricReference("x"), null),
            issueSet = MetricFlowQueryResolutionIssueSet.EMPTY,
            filterLocationPath = MetricFlowQueryResolutionPath.EMPTY,
            objectBuilderStr = "x",
        )
    }
}
