package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.spec.pattern.DimensionPattern
import cc.monomer.metricflow.domain.spec.pattern.EntityLinkPattern
import cc.monomer.metricflow.domain.spec.pattern.MatchListSpecPattern
import cc.monomer.metricflow.domain.spec.pattern.MetricSpecPattern
import cc.monomer.metricflow.domain.spec.pattern.MetricTimeDefaultGranularityPattern
import cc.monomer.metricflow.domain.spec.pattern.MetricTimePattern
import cc.monomer.metricflow.domain.spec.pattern.MinimumTimeGrainPattern
import cc.monomer.metricflow.domain.spec.pattern.NoGroupByMetricPattern
import cc.monomer.metricflow.domain.spec.pattern.NoneDatePartPattern
import cc.monomer.metricflow.domain.spec.pattern.ParameterSetField
import cc.monomer.metricflow.domain.spec.pattern.SpecPatternParameterSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecPatternTest {

    private fun day(grain: TimeGranularity = TimeGranularity.DAY, elementName: String = METRIC_TIME_ELEMENT_NAME) =
        TimeDimensionSpec(
            elementName = elementName,
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(grain),
            datePart = null,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )

    @Test
    fun `MatchListSpecPattern returns only listed specs`() {
        val a = DimensionSpec.fromElementName("a")
        val b = DimensionSpec.fromElementName("b")
        val pattern = MatchListSpecPattern.create(listOf<InstanceSpec>(a))
        assertEquals(listOf<InstanceSpec>(a), pattern.match(listOf(a, b)))
    }

    @Test
    fun `MetricSpecPattern matches by metric reference`() {
        val booking = MetricSpec.fromElementName("bookings")
        val revenue = MetricSpec.fromElementName("revenue")
        val pat = MetricSpecPattern(MetricReference("bookings"), descending = null)
        assertEquals(listOf(booking), pat.match(listOf(booking, revenue)))
    }

    @Test
    fun `MetricTimePattern selects only metric_time specs`() {
        val mt = day()
        val ds = day(elementName = "ds")
        assertEquals(listOf(mt), MetricTimePattern.match(listOf(mt, ds)))
    }

    @Test
    fun `NoneDatePartPattern filters out date-part time dim specs`() {
        val grain = day()
        val datePartOnly = TimeDimensionSpec(
            elementName = "ds",
            entityLinks = emptyList(),
            timeGranularity = null,
            datePart = cc.monomer.metricflow.domain.manifest.model.enums.DatePart.YEAR,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )
        val out = NoneDatePartPattern.match(listOf(grain, datePartOnly))
        assertEquals(listOf<LinkableInstanceSpec>(grain), out)
    }

    @Test
    fun `NoGroupByMetricPattern drops group-by metric specs`() {
        val gbm = GroupByMetricSpec(
            elementName = "bookings",
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = listOf(EntityReference("user")),
            alias = null,
        )
        val dim = DimensionSpec.fromElementName("country")
        val out = NoGroupByMetricPattern.match(listOf(gbm, dim))
        assertEquals(listOf<LinkableInstanceSpec>(dim), out)
    }

    @Test
    fun `MinimumTimeGrainPattern picks finest grain per spec key`() {
        val dayMt = day(TimeGranularity.DAY)
        val monthMt = day(TimeGranularity.MONTH)
        val country = DimensionSpec.fromElementName("country")
        val out = MinimumTimeGrainPattern.match(listOf(dayMt, monthMt, country))
        // The metric_time bucket collapses to its finest grain (DAY); country passes through.
        assertTrue(dayMt in out)
        assertFalse(monthMt in out)
        assertTrue(country in out)
    }

    @Test
    fun `MetricTimeDefaultGranularityPattern picks default grain if present`() {
        val dayMt = day(TimeGranularity.DAY)
        val monthMt = day(TimeGranularity.MONTH)
        val pat = MetricTimeDefaultGranularityPattern(maxMetricDefaultTimeGranularity = TimeGranularity.MONTH)
        val out = pat.match(listOf(dayMt, monthMt))
        assertTrue(monthMt in out)
        assertFalse(dayMt in out)
    }

    @Test
    fun `EntityLinkPattern matches by suffix on entity links`() {
        val parent = DimensionSpec(
            elementName = "country",
            entityLinks = listOf(EntityReference("booking"), EntityReference("listing")),
            alias = null,
        )
        val direct = DimensionSpec(
            elementName = "country",
            entityLinks = listOf(EntityReference("listing")),
            alias = null,
        )
        val pattern = EntityLinkPattern(
            SpecPatternParameterSet.fromParameters(
                fieldsToCompare = listOf(ParameterSetField.ELEMENT_NAME, ParameterSetField.ENTITY_LINKS),
                elementName = "country",
                entityLinks = listOf(EntityReference("listing")),
                timeGranularityName = null,
                datePart = null,
                metricSubqueryEntityLinks = null,
                descending = null,
            ),
        )
        val matched = pattern.match(listOf(parent, direct))
        // Both match the entity-link suffix; "shortest path" wins → only `direct`.
        assertEquals(listOf<LinkableInstanceSpec>(direct), matched)
    }

    @Test
    fun `DimensionPattern excludes time dimensions when flag is false`() {
        val dim = DimensionSpec.fromElementName("country")
        val td = day(elementName = "ds")
        val pattern = DimensionPattern(
            parameterSet = SpecPatternParameterSet.fromParameters(
                fieldsToCompare = listOf(ParameterSetField.ELEMENT_NAME),
                elementName = "country",
                entityLinks = null,
                timeGranularityName = null,
                datePart = null,
                metricSubqueryEntityLinks = null,
                descending = null,
            ),
            includeTimeDimensions = false,
        )
        val matched = pattern.match(listOf(dim, td))
        assertEquals(listOf<LinkableInstanceSpec>(dim), matched)
    }
}
