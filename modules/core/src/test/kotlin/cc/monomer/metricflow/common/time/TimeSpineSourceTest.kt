package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticVersion
import cc.monomer.metricflow.domain.manifest.model.TimeSpine
import cc.monomer.metricflow.domain.manifest.model.TimeSpineCustomGranularityColumn
import cc.monomer.metricflow.domain.manifest.model.TimeSpinePrimaryColumn
import cc.monomer.metricflow.domain.manifest.model.TimeSpineTableConfiguration
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TimeSpineSourceTest {

    private fun emptyManifest(
        timeSpines: List<TimeSpine> = emptyList(),
        legacy: List<TimeSpineTableConfiguration> = emptyList(),
    ): SemanticManifest = SemanticManifest(
        semanticModels = emptyList(),
        metrics = emptyList(),
        projectConfiguration = ProjectConfiguration(
            timeSpineTableConfigurations = legacy,
            metadata = null,
            dsiPackageVersion = SemanticVersion.UNKNOWN_VERSION_SENTINEL,
            timeSpines = timeSpines,
        ),
        savedQueries = emptyList(),
    )

    @Test
    fun `empty manifest produces no time spine sources`() {
        assertEquals(
            emptyMap(),
            TimeSpineSource.buildStandardTimeSpineSources(emptyManifest()),
        )
    }

    @Test
    fun `manifest with one time spine produces one source`() {
        val timeSpine = TimeSpine(
            nodeRelation = NodeRelation(
                alias = "calendar",
                schemaName = "ana",
                database = null,
                relationName = "ana.calendar",
            ),
            primaryColumn = TimeSpinePrimaryColumn(name = "ds", timeGranularity = TimeGranularity.DAY),
            customGranularities = emptyList(),
        )
        val sources = TimeSpineSource.buildStandardTimeSpineSources(emptyManifest(timeSpines = listOf(timeSpine)))
        assertEquals(1, sources.size)
        val source = sources[TimeGranularity.DAY]
        assertNotNull(source)
        assertEquals("ana", source.sqlTable.schemaName)
        assertEquals("calendar", source.sqlTable.tableName)
        assertEquals("ds", source.baseColumn)
        assertEquals(TimeGranularity.DAY, source.baseGranularity)
    }

    @Test
    fun `legacy config fills granularity gaps not covered by new config`() {
        val timeSpine = TimeSpine(
            nodeRelation = NodeRelation(
                alias = "calendar",
                schemaName = "ana",
                database = null,
                relationName = "ana.calendar",
            ),
            primaryColumn = TimeSpinePrimaryColumn(name = "ds", timeGranularity = TimeGranularity.DAY),
            customGranularities = emptyList(),
        )
        val legacy = TimeSpineTableConfiguration(
            location = "ana.calendar_hour",
            columnName = "ts_hour",
            grain = TimeGranularity.HOUR,
        )
        val sources = TimeSpineSource.buildStandardTimeSpineSources(
            emptyManifest(timeSpines = listOf(timeSpine), legacy = listOf(legacy)),
        )
        assertEquals(setOf(TimeGranularity.DAY, TimeGranularity.HOUR), sources.keys)
        assertEquals("ts_hour", sources[TimeGranularity.HOUR]!!.baseColumn)
    }

    @Test
    fun `legacy config is ignored when the same granularity is in the new config`() {
        val timeSpine = TimeSpine(
            nodeRelation = NodeRelation(
                alias = "calendar",
                schemaName = "ana",
                database = null,
                relationName = "ana.calendar",
            ),
            primaryColumn = TimeSpinePrimaryColumn(name = "ds", timeGranularity = TimeGranularity.DAY),
            customGranularities = emptyList(),
        )
        val legacy = TimeSpineTableConfiguration(
            location = "ana.calendar_legacy",
            columnName = "legacy_ds",
            grain = TimeGranularity.DAY,
        )
        val sources = TimeSpineSource.buildStandardTimeSpineSources(
            emptyManifest(timeSpines = listOf(timeSpine), legacy = listOf(legacy)),
        )
        assertEquals("ds", sources[TimeGranularity.DAY]!!.baseColumn)
    }

    @Test
    fun `buildCustomGranularities lifts each custom column to an expanded granularity`() {
        val source = TimeSpineSource(
            sqlTable = cc.monomer.metricflow.domain.spec.bind.SqlTable(
                schemaName = "ana",
                tableName = "calendar",
            ),
            baseColumn = "ds",
            baseGranularity = TimeGranularity.DAY,
            customGranularities = listOf(
                TimeSpineCustomGranularityColumn(name = "fiscal_quarter", columnName = "fq"),
                TimeSpineCustomGranularityColumn(name = "fiscal_year", columnName = "fy"),
            ),
        )
        val customs = TimeSpineSource.buildCustomGranularities(listOf(source))
        assertEquals(setOf("fiscal_quarter", "fiscal_year"), customs.keys)
        assertEquals(TimeGranularity.DAY, customs["fiscal_quarter"]!!.baseGranularity)
    }

    @Test
    fun `buildCustomTimeSpineSources points each custom name back at its source`() {
        val source = TimeSpineSource(
            sqlTable = cc.monomer.metricflow.domain.spec.bind.SqlTable(
                schemaName = "ana",
                tableName = "calendar",
            ),
            baseColumn = "ds",
            baseGranularity = TimeGranularity.DAY,
            customGranularities = listOf(
                TimeSpineCustomGranularityColumn(name = "fiscal_quarter", columnName = "fq"),
            ),
        )
        val customs = TimeSpineSource.buildCustomTimeSpineSources(listOf(source))
        assertEquals(source, customs["fiscal_quarter"])
    }
}
