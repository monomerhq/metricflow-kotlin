package cc.monomer.metricflow.domain.manifest.transformation

import cc.monomer.metricflow.domain.manifest.model.CumulativeTypeParams
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricInputMeasure
import cc.monomer.metricflow.domain.manifest.model.MetricTimeWindow
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.Measure
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.transformation.rules.AddInputMetricMeasuresRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.BooleanAggregationRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.BooleanMeasureAggregationRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.ConvertCountMetricToSumRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.ConvertCountToSumRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.LowerCaseNamesRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.RemovePluralFromWindowGranularityRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.SetCumulativeTypeParamsRule
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Focused per-rule tests built on hand-crafted minimal manifests. Each test exercises one rule
 * in isolation to make regressions easy to localise. The Python-parity test is the load-bearing
 * acceptance bar; these are diagnostic.
 */
class RuleUnitTests {

    private val emptyManifest = SemanticManifest(
        semanticModels = emptyList(),
        metrics = emptyList(),
        projectConfiguration = ProjectConfiguration(),
    )

    private fun simpleModel(
        name: String,
        measures: List<Measure> = emptyList(),
    ): SemanticModel = SemanticModel(
        name = name,
        nodeRelation = NodeRelation(alias = "t", schemaName = "s"),
        measures = measures,
    )

    @Test
    fun `LowerCaseNamesRule lowercases model, measure, entity, dimension, defaults`() {
        val input = emptyManifest.copy(
            semanticModels = listOf(
                simpleModel(
                    name = "Bookings",
                    measures = listOf(Measure(name = "Bookings_M", agg = AggregationType.SUM)),
                ),
            ),
        )
        val out = LowerCaseNamesRule.transformModel(input)
        assertEquals("bookings", out.semanticModels[0].name)
        assertEquals("bookings_m", out.semanticModels[0].measures[0].name)
    }

    @Test
    fun `BooleanMeasureAggregationRule rewrites SUM_BOOLEAN measure`() {
        val input = emptyManifest.copy(
            semanticModels = listOf(
                simpleModel(
                    name = "m",
                    measures = listOf(
                        Measure(name = "is_active", agg = AggregationType.SUM_BOOLEAN, expr = "active_flag"),
                    ),
                ),
            ),
        )
        val out = BooleanMeasureAggregationRule.transformModel(input)
        val measure = out.semanticModels[0].measures[0]
        assertEquals(AggregationType.SUM, measure.agg)
        assertEquals("CASE WHEN active_flag THEN 1 ELSE 0 END", measure.expr)
    }

    @Test
    fun `BooleanAggregationRule rewrites SUM_BOOLEAN metric`() {
        val metric = Metric(
            name = "active_users",
            type = MetricType.SIMPLE,
            typeParams = MetricTypeParams(
                expr = "is_active",
                metricAggregationParams = cc.monomer.metricflow.domain.manifest.model.MetricAggregationParams(
                    semanticModel = "users",
                    agg = AggregationType.SUM_BOOLEAN,
                ),
            ),
        )
        val out = BooleanAggregationRule.transformModel(emptyManifest.copy(metrics = listOf(metric)))
        val m = out.metrics[0]
        assertEquals(AggregationType.SUM, m.typeParams.metricAggregationParams!!.agg)
        assertEquals("CASE WHEN is_active THEN 1 ELSE 0 END", m.typeParams.expr)
    }

    @Test
    fun `ConvertCountToSumRule wraps non-one expressions`() {
        val input = emptyManifest.copy(
            semanticModels = listOf(
                simpleModel(
                    name = "m",
                    measures = listOf(
                        Measure(name = "non_null_id", agg = AggregationType.COUNT, expr = "user_id"),
                        Measure(name = "all_rows", agg = AggregationType.COUNT, expr = "1"),
                    ),
                ),
            ),
        )
        val out = ConvertCountToSumRule.transformModel(input)
        val byName = out.semanticModels[0].measures.associateBy { it.name }
        assertEquals(AggregationType.SUM, byName["non_null_id"]!!.agg)
        assertEquals("CASE WHEN user_id IS NOT NULL THEN 1 ELSE 0 END", byName["non_null_id"]!!.expr)
        assertEquals(AggregationType.SUM, byName["all_rows"]!!.agg)
        assertEquals("1", byName["all_rows"]!!.expr) // unchanged
    }

    @Test
    fun `ConvertCountToSumRule throws when COUNT measure lacks expr`() {
        val input = emptyManifest.copy(
            semanticModels = listOf(
                simpleModel(
                    name = "m",
                    measures = listOf(Measure(name = "x", agg = AggregationType.COUNT)),
                ),
            ),
        )
        assertThrows<ModelTransformError> { ConvertCountToSumRule.transformModel(input) }
    }

    @Test
    fun `ConvertCountMetricToSumRule rewrites metric with metric_aggregation_params`() {
        val metric = Metric(
            name = "cm",
            type = MetricType.SIMPLE,
            typeParams = MetricTypeParams(
                expr = "user_id",
                metricAggregationParams = cc.monomer.metricflow.domain.manifest.model.MetricAggregationParams(
                    semanticModel = "users",
                    agg = AggregationType.COUNT,
                ),
            ),
        )
        val out = ConvertCountMetricToSumRule.transformModel(emptyManifest.copy(metrics = listOf(metric)))
        val m = out.metrics[0]
        assertEquals(AggregationType.SUM, m.typeParams.metricAggregationParams!!.agg)
        assertEquals("CASE WHEN user_id IS NOT NULL THEN 1 ELSE 0 END", m.typeParams.expr)
    }

    @Test
    fun `SetCumulativeTypeParamsRule backfills cumulative_type_params from legacy fields`() {
        val metric = Metric(
            name = "cumulative_bookings",
            type = MetricType.CUMULATIVE,
            typeParams = MetricTypeParams(
                window = MetricTimeWindow(count = 7, granularity = "day"),
                grainToDate = TimeGranularity.WEEK,
                measure = MetricInputMeasure(name = "bookings"),
            ),
        )
        val out = SetCumulativeTypeParamsRule.transformModel(emptyManifest.copy(metrics = listOf(metric)))
        val ctp = out.metrics[0].typeParams.cumulativeTypeParams!!
        assertEquals(MetricTimeWindow(count = 7, granularity = "day"), ctp.window)
        assertEquals("week", ctp.grainToDate)
    }

    @Test
    fun `SetCumulativeTypeParamsRule preserves period_agg default when populating empty CTP`() {
        val metric = Metric(
            name = "c",
            type = MetricType.CUMULATIVE,
            typeParams = MetricTypeParams(measure = MetricInputMeasure(name = "x")),
        )
        val out = SetCumulativeTypeParamsRule.transformModel(emptyManifest.copy(metrics = listOf(metric)))
        // Default `periodAgg = FIRST` (model-side default).
        assertEquals(CumulativeTypeParams(), out.metrics[0].typeParams.cumulativeTypeParams)
    }

    @Test
    fun `RemovePluralFromWindowGranularityRule trims s on cumulative window`() {
        val metric = Metric(
            name = "c",
            type = MetricType.CUMULATIVE,
            typeParams = MetricTypeParams(
                cumulativeTypeParams = CumulativeTypeParams(window = MetricTimeWindow(count = 3, granularity = "days")),
            ),
        )
        val out = RemovePluralFromWindowGranularityRule.transformModel(emptyManifest.copy(metrics = listOf(metric)))
        assertEquals("day", out.metrics[0].typeParams.cumulativeTypeParams!!.window!!.granularity)
    }

    @Test
    fun `RemovePluralFromWindowGranularityRule trims s on derived metric offset_window`() {
        val metric = Metric(
            name = "d",
            type = MetricType.DERIVED,
            typeParams = MetricTypeParams(
                metrics = listOf(
                    MetricInput(name = "x", offsetWindow = MetricTimeWindow(count = 2, granularity = "weeks")),
                ),
            ),
        )
        val out = RemovePluralFromWindowGranularityRule.transformModel(emptyManifest.copy(metrics = listOf(metric)))
        assertEquals("week", out.metrics[0].typeParams.metrics!![0].offsetWindow!!.granularity)
    }

    @Test
    fun `AddInputMetricMeasuresRule populates input_measures for derived metrics`() {
        val simple = Metric(
            name = "leaf",
            type = MetricType.SIMPLE,
            typeParams = MetricTypeParams(
                measure = MetricInputMeasure(name = "leaf_m"),
                inputMeasures = listOf(MetricInputMeasure(name = "leaf_m")),
            ),
        )
        val derived = Metric(
            name = "wrap",
            type = MetricType.DERIVED,
            typeParams = MetricTypeParams(
                metrics = listOf(MetricInput(name = "leaf")),
            ),
        )
        val out = AddInputMetricMeasuresRule.transformModel(emptyManifest.copy(metrics = listOf(simple, derived)))
        val wrap = out.metrics.first { it.name == "wrap" }
        assertEquals(listOf(MetricInputMeasure(name = "leaf_m")), wrap.typeParams.inputMeasures)
    }
}
