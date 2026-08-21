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
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

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

    @Test
    fun `chooseTimeSpineSources selects the coarsest compatible standard spine`() {
        val sources = linkedMapOf(
            TimeGranularity.SECOND to source(TimeGranularity.SECOND, "second_spine"),
            TimeGranularity.MINUTE to source(TimeGranularity.MINUTE, "minute_spine"),
            TimeGranularity.DAY to source(TimeGranularity.DAY, "day_spine"),
        )

        val selected = TimeSpineSource.chooseTimeSpineSources(
            requiredTimeSpineSpecs = sequenceOf(timeSpec(TimeGranularity.HOUR), timeSpec(TimeGranularity.DAY)),
            timeSpineSources = sources,
        )

        assertEquals(listOf(sources.getValue(TimeGranularity.MINUTE)), selected)
    }

    @Test
    fun `chooseTimeSpineSources adds a standard spine when custom spine cannot satisfy it`() {
        val customSpine = source(
            baseGranularity = TimeGranularity.MONTH,
            tableName = "fiscal_spine",
            customName = "fiscal_month",
        )
        val daySpine = source(TimeGranularity.DAY, "day_spine")
        val sources = linkedMapOf(
            TimeGranularity.MONTH to customSpine,
            TimeGranularity.DAY to daySpine,
        )

        val selected = TimeSpineSource.chooseTimeSpineSources(
            requiredTimeSpineSpecs = sequenceOf(
                customTimeSpec("fiscal_month", TimeGranularity.MONTH),
                timeSpec(TimeGranularity.DAY),
            ),
            timeSpineSources = sources,
        )

        assertEquals(listOf(daySpine, customSpine), selected)
    }

    @Test
    fun `chooseTimeSpineSources uses a custom spine when it also satisfies standard grains`() {
        val customSpine = source(
            baseGranularity = TimeGranularity.DAY,
            tableName = "fiscal_spine",
            customName = "fiscal_month",
        )
        val monthSpine = source(TimeGranularity.MONTH, "month_spine")
        val sources = linkedMapOf(
            TimeGranularity.DAY to customSpine,
            TimeGranularity.MONTH to monthSpine,
        )

        val selected = TimeSpineSource.chooseTimeSpineSources(
            requiredTimeSpineSpecs = sequenceOf(
                customTimeSpec("fiscal_month", TimeGranularity.DAY),
                timeSpec(TimeGranularity.MONTH),
            ),
            timeSpineSources = sources,
        )

        assertEquals(listOf(customSpine), selected)
    }

    @Test
    fun `chooseTimeSpineSources rejects an empty requirement`() {
        assertFailsWith<IllegalArgumentException> {
            TimeSpineSource.chooseTimeSpineSources(
                requiredTimeSpineSpecs = emptySequence(),
                timeSpineSources = emptyMap(),
            )
        }
    }

    @Test
    fun `chooseTimeSpineSources reports when no configured spine is fine enough`() {
        assertFailsWith<cc.monomer.metricflow.common.errors.SemanticManifestConfigurationError> {
            TimeSpineSource.chooseTimeSpineSources(
                requiredTimeSpineSpecs = sequenceOf(timeSpec(TimeGranularity.DAY)),
                timeSpineSources = mapOf(
                    TimeGranularity.MONTH to source(TimeGranularity.MONTH, "month_spine"),
                ),
            )
        }
    }

    private fun source(
        baseGranularity: TimeGranularity,
        tableName: String,
        customName: String? = null,
    ): TimeSpineSource = TimeSpineSource(
        sqlTable = SqlTable(schemaName = "analytics", tableName = tableName),
        baseColumn = "ds",
        baseGranularity = baseGranularity,
        customGranularities = customName?.let {
            listOf(TimeSpineCustomGranularityColumn(name = it, columnName = it))
        } ?: emptyList(),
    )

    private fun timeSpec(granularity: TimeGranularity): TimeDimensionSpec = TimeDimensionSpec(
        elementName = "metric_time",
        entityLinks = emptyList(),
        timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(granularity),
        datePart = null,
        aggregationState = null,
        windowFunctions = emptyList(),
        alias = null,
    )

    private fun customTimeSpec(name: String, baseGranularity: TimeGranularity): TimeDimensionSpec =
        TimeDimensionSpec(
            elementName = "metric_time",
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity(name = name, baseGranularity = baseGranularity),
            datePart = null,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )
}
