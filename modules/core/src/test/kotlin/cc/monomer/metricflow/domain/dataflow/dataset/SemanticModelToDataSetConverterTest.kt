package cc.monomer.metricflow.domain.dataflow.dataset

import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricAggregationParams
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
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticModelToDataSetConverterTest {

    private class StubResolver : ColumnAssociationResolver {
        override fun resolveSpec(spec: InstanceSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.dunderName)

        override fun withOptions(dunderPrefixSimpleMetricInputs: Boolean): ColumnAssociationResolver = this
    }

    private fun manifest(): SemanticManifest = SemanticManifest(
        semanticModels = listOf(
            SemanticModel(
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
                    Dimension(name = "is_instant", type = DimensionType.CATEGORICAL),
                ),
            ),
        ),
        metrics = listOf(
            Metric(
                name = "bookings",
                type = MetricType.SIMPLE,
                typeParams = MetricTypeParams(
                    metricAggregationParams = MetricAggregationParams(
                        semanticModel = "bookings_source",
                        agg = AggregationType.SUM,
                        aggTimeDimension = "ds",
                    ),
                ),
            ),
        ),
        projectConfiguration = ProjectConfiguration(
            timeSpines = listOf(
                TimeSpine(
                    nodeRelation = NodeRelation(
                        alias = "time_spine",
                        schemaName = "demo",
                        relationName = "demo.time_spine",
                    ),
                    primaryColumn = TimeSpinePrimaryColumn(
                        name = "ds",
                        timeGranularity = TimeGranularity.DAY,
                    ),
                ),
            ),
        ),
    )

    @BeforeEach
    fun resetIds() {
        SequentialIdGenerator.reset()
    }

    @Test
    fun `createSqlSourceDataSet produces a SemanticModelDataSet with simple metric inputs`() {
        val manifest = manifest()
        val manifestLookup = SemanticManifestLookup(manifest)
        val manifestObjectLookup = ManifestObjectLookup(manifest)

        val converter = SemanticModelToDataSetConverter(
            columnAssociationResolver = StubResolver(),
            manifestLookup = manifestLookup,
            manifestObjectLookup = manifestObjectLookup,
        )

        val ds = converter.createSqlSourceDataSet(SemanticModelReference("bookings_source"))

        assertEquals("bookings_source", ds.semanticModelReference.semanticModelName)
        val instances = ds.instanceSet

        // One simple metric input "bookings".
        assertEquals(1, instances.simpleMetricInputInstances.size)
        assertEquals("bookings", instances.simpleMetricInputInstances.first().spec.elementName)

        // Categorical dimensions emitted per entity link variant — `is_instant` × {empty, [booking], [listing]}.
        val categoricalNames = instances.dimensionInstances.map { it.spec.elementName }
        assertTrue(categoricalNames.all { it == "is_instant" })
        assertTrue(instances.dimensionInstances.size >= 1)

        // Time dimensions for `ds` — base grain + larger grains (week, month, quarter, year) +
        // every date_part variant, emitted per entity-link variant.
        assertTrue(instances.timeDimensionInstances.isNotEmpty())
        assertTrue(instances.timeDimensionInstances.any { it.spec.elementName == "ds" })

        // Entities — `booking` (primary) + `listing` (foreign), emitted per entity-link variant.
        val entityElementNames = instances.entityInstances.map { it.spec.elementName }.toSet()
        assertTrue("booking" in entityElementNames || "listing" in entityElementNames)

        // SELECT statement should contain a non-empty list of columns.
        val select = ds.checkedSqlSelectNode
        assertNotNull(select)
        assertTrue(select.selectColumns.isNotEmpty())
    }

    @Test
    fun `buildTimeSpineSourceDataSet builds a dataset for the time spine`() {
        val manifest = manifest()
        val manifestLookup = SemanticManifestLookup(manifest)
        val manifestObjectLookup = ManifestObjectLookup(manifest)

        val converter = SemanticModelToDataSetConverter(
            columnAssociationResolver = StubResolver(),
            manifestLookup = manifestLookup,
            manifestObjectLookup = manifestObjectLookup,
        )

        val spine = manifestLookup.timeSpineSources.values.first()
        val ds = converter.buildTimeSpineSourceDataSet(spine)

        // Has time-dim instances + a select statement.
        assertTrue(ds.instanceSet.timeDimensionInstances.isNotEmpty())
        val select = ds.checkedSqlSelectNode
        assertTrue(select.selectColumns.isNotEmpty())
    }
}
