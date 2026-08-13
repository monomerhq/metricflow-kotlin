package cc.monomer.metricflow.domain.dataflow.optimizer

import cc.monomer.metricflow.domain.dataflow.optimizer.source_scan.SourceScanOptimizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataflowPlanOptimizerFactoryTest {

    @Test
    fun `factory wires the source-scan optimizer when requested`() {
        val factory = DataflowPlanOptimizerFactory()
        val optimizers = factory.getOptimizers(setOf(DataflowPlanOptimization.SOURCE_SCAN))
        assertEquals(1, optimizers.size)
        assertTrue(optimizers[0] is SourceScanOptimizer)
    }

    @Test
    fun `passthrough optimization is silently skipped`() {
        val factory = DataflowPlanOptimizerFactory()
        val optimizers = factory.getOptimizers(
            setOf(DataflowPlanOptimization.PASSTHROUGH_METRIC_EVALUATION),
        )
        assertTrue(optimizers.isEmpty())
    }

    @Test
    fun `defaults only enable source scan`() {
        assertEquals(setOf(DataflowPlanOptimization.SOURCE_SCAN), DataflowPlanOptimization.enabledOptimizations())
    }

    @Test
    fun `all_optimizations contains every enum member`() {
        assertEquals(
            DataflowPlanOptimization.entries.toSet(),
            DataflowPlanOptimization.allOptimizations(),
        )
    }
}
