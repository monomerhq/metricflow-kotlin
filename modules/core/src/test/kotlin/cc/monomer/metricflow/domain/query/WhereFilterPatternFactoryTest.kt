package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.parameterset.DimensionCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.EntityCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.MetricCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.TimeDimensionCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.query.filter.DefaultWhereFilterPatternFactory
import cc.monomer.metricflow.domain.spec.pattern.DimensionPattern
import cc.monomer.metricflow.domain.spec.pattern.EntityPattern
import cc.monomer.metricflow.domain.spec.pattern.GroupByMetricPattern
import cc.monomer.metricflow.domain.spec.pattern.TimeDimensionPattern
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class WhereFilterPatternFactoryTest {

    private val factory = DefaultWhereFilterPatternFactory()

    @Test
    fun `createForDimension builds DimensionPattern`() {
        val pattern = factory.createForDimension(
            DimensionCallParameterSet(
                entityPath = listOf(EntityReference("listing")),
                dimensionReference = DimensionReference("country"),
                descending = null,
            ),
        )
        assertTrue(pattern is DimensionPattern)
    }

    @Test
    fun `createForTimeDimension builds TimeDimensionPattern`() {
        val pattern = factory.createForTimeDimension(
            TimeDimensionCallParameterSet(
                entityPath = emptyList(),
                timeDimensionReference = TimeDimensionReference("metric_time"),
                timeGranularityName = "day",
                datePart = DatePart.YEAR,
                descending = null,
            ),
        )
        assertTrue(pattern is TimeDimensionPattern)
    }

    @Test
    fun `createForEntity builds EntityPattern`() {
        val pattern = factory.createForEntity(
            EntityCallParameterSet(
                entityPath = emptyList(),
                entityReference = EntityReference("listing"),
                descending = null,
            ),
        )
        assertTrue(pattern is EntityPattern)
    }

    @Test
    fun `createForMetric builds GroupByMetricPattern`() {
        val pattern = factory.createForMetric(
            MetricCallParameterSet(
                metricReference = MetricReference("bookings"),
                groupBy = emptyList(),
                descending = null,
            ),
        )
        assertTrue(pattern is GroupByMetricPattern)
    }
}
