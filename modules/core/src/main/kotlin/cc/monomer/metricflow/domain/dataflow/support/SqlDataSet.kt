package cc.monomer.metricflow.domain.dataflow.support

import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Marker interface used by [cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode]
 * to refer to a dataset without depending on the full
 * [cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet] class.
 *
 * The concrete class
 * [cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet] (W9b) implements this
 * interface. Tests that don't need the full instance-set / SQL-node plumbing implement this
 * directly with a stub (see `FakeSqlDataSet` in `DataflowPlanNodeStructureTest`).
 *
 * The split lets the W9a node layer compile without depending on the W9b dataset/instance
 * machinery — a learning-readability win when readers trace the dataflow node API in isolation.
 */
interface SqlDataSet {
    /**
     * The semantic model this dataset reads from, if any. Time-spine reads return `null`.
     * Port of Python `SqlDataSet.semantic_model_reference`.
     */
    val semanticModelReference: SemanticModelReference?
}
