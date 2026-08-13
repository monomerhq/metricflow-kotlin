package cc.monomer.metricflow.domain.metric_evaluation

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricAggregationParams
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
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
 * Contains:
 * - One semantic model `bookings_source` with a primary `booking` entity, a foreign `listing`
 *   entity, a `bookings` measure, and a `ds` time dimension.
 * - One semantic model `listings_source` with a primary `listing` entity and a `listings`
 *   measure.
 * - Three metrics: simple `bookings`, simple `listings`, and derived `bookings_per_listing`
 *   (which depends on both simples).
 * - A daily time spine.
 */
internal object MetricEvaluationFixtures {

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
            Dimension(
                name = "ds",
                type = DimensionType.TIME,
                typeParams = DimensionTypeParams(TimeGranularity.DAY),
            ),
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
        ),
        measures = listOf(
            Measure(name = "listings", agg = AggregationType.SUM, expr = "1"),
        ),
        dimensions = listOf(
            Dimension(
                name = "ds",
                type = DimensionType.TIME,
                typeParams = DimensionTypeParams(TimeGranularity.DAY),
            ),
        ),
    )

    fun bookingsMetric(): Metric = Metric(
        name = "bookings",
        type = MetricType.SIMPLE,
        typeParams = MetricTypeParams(
            metricAggregationParams = MetricAggregationParams(
                semanticModel = "bookings_source",
                agg = AggregationType.SUM,
                aggTimeDimension = "ds",
            ),
        ),
    )

    fun listingsMetric(): Metric = Metric(
        name = "listings",
        type = MetricType.SIMPLE,
        typeParams = MetricTypeParams(
            metricAggregationParams = MetricAggregationParams(
                semanticModel = "listings_source",
                agg = AggregationType.SUM,
                aggTimeDimension = "ds",
            ),
        ),
    )

    fun bookingsPerListingMetric(): Metric = Metric(
        name = "bookings_per_listing",
        type = MetricType.DERIVED,
        typeParams = MetricTypeParams(
            expr = "bookings / listings",
            metrics = listOf(
                MetricInput(name = "bookings"),
                MetricInput(name = "listings"),
            ),
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
        metrics = listOf(bookingsMetric(), listingsMetric(), bookingsPerListingMetric()),
        projectConfiguration = ProjectConfiguration(
            timeSpines = listOf(timeSpine()),
        ),
    )
}
