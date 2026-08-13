package cc.monomer.metricflow.domain.semantic_graph

/**
 * Cluster name constants used to group related nodes in the semantic graph
 * (mostly for visualization but useful for grouping in tests).
 *
 * Port of `metricflow_semantics/semantic_graph/sg_constant.py::ClusterNameFactory`.
 */
object ClusterNameFactory {
    const val TIME: String = "time"
    const val KEY: String = "key"
    const val CONFIGURED_ENTITY: String = "configured_entity"
    const val DIMENSION: String = "dimension"
    const val METRIC: String = "metric"
    const val TIME_DIMENSION: String = "time_dimension"

    /** Cluster name for nodes that represent a configured entity in a given model. */
    fun getNameForConfiguredEntity(entityName: String, modelId: SemanticModelId): String =
        "${modelId.modelName}.$entityName"

    /** Cluster name that groups nodes associated with a specific semantic model. */
    fun getNameForModel(modelId: SemanticModelId): String = modelId.modelName
}
