package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.MetricAggregationParams
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.TimeSpine
import cc.monomer.metricflow.domain.manifest.model.TimeSpinePrimaryColumn
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.DimensionTypeParams
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.element.Measure
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * Hand-built manifest fixtures used by unit tests in this module.
 *
 * The shape is the minimum needed by the lookup tests: two semantic models, a primary entity
 * on each, a couple of dimensions (including a time dimension), a measure, a SIMPLE metric, and
 * a derived metric. A single time spine is configured so [SemanticManifestLookup] can be
 * constructed.
 */
internal object LookupFixtures {

    fun bookingsModel(): SemanticModel = SemanticModel(
        name = "bookings_source",
        nodeRelation = NodeRelation(
            alias = "fct_bookings",
            schemaName = "demo",
            relationName = "demo.fct_bookings",
        ),
        primaryEntity = null,
        entities = listOf(
            Entity(name = "booking", type = EntityType.PRIMARY),
            Entity(name = "listing", type = EntityType.FOREIGN),
        ),
        measures = listOf(
            Measure(name = "bookings", agg = AggregationType.SUM, expr = "1"),
        ),
        dimensions = listOf(
            Dimension(name = "ds", type = DimensionType.TIME, typeParams = DimensionTypeParams(TimeGranularity.DAY)),
            Dimension(name = "is_instant", type = DimensionType.CATEGORICAL),
        ),
    )

    fun listingsModel(): SemanticModel = SemanticModel(
        name = "listings_source",
        nodeRelation = NodeRelation(
            alias = "dim_listings",
            schemaName = "demo",
            relationName = "demo.dim_listings",
        ),
        primaryEntity = null,
        entities = listOf(
            Entity(name = "listing", type = EntityType.PRIMARY),
            Entity(name = "user", type = EntityType.FOREIGN),
        ),
        measures = emptyList(),
        dimensions = listOf(
            Dimension(name = "ds", type = DimensionType.TIME, typeParams = DimensionTypeParams(TimeGranularity.DAY)),
            Dimension(name = "country", type = DimensionType.CATEGORICAL),
        ),
    )

    fun simpleBookingsMetric(): Metric = Metric(
        name = "bookings_count",
        type = MetricType.SIMPLE,
        typeParams = MetricTypeParams(
            metricAggregationParams = MetricAggregationParams(
                semanticModel = "bookings_source",
                agg = AggregationType.SUM,
            ),
        ),
    )

    fun derivedMetric(): Metric = Metric(
        name = "doubled_bookings",
        type = MetricType.DERIVED,
        typeParams = MetricTypeParams(
            expr = "bookings_count * 2",
            metrics = listOf(MetricInput(name = "bookings_count")),
        ),
    )

    fun timeSpine(): TimeSpine = TimeSpine(
        nodeRelation = NodeRelation(
            alias = "time_spine",
            schemaName = "demo",
            relationName = "demo.time_spine",
        ),
        primaryColumn = TimeSpinePrimaryColumn(name = "ds", timeGranularity = TimeGranularity.DAY),
    )

    fun manifest(): SemanticManifest = SemanticManifest(
        semanticModels = listOf(bookingsModel(), listingsModel()),
        metrics = listOf(simpleBookingsMetric(), derivedMetric()),
        projectConfiguration = ProjectConfiguration(
            timeSpines = listOf(timeSpine()),
        ),
    )
}
