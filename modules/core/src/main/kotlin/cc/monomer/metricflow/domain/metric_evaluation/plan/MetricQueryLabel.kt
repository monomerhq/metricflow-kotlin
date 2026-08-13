package cc.monomer.metricflow.domain.metric_evaluation.plan

import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel

/**
 * Label that identifies a `TopLevelQueryNode` in a `MetricEvaluationPlan`.
 *
 * Port of `metricflow.metric_evaluation.plan.me_labels.TopLevelQueryLabel`.
 *
 * Python uses a `Singleton` mix-in; the Kotlin port models it as a
 * `data object` so all references compare equal and the visitor pattern can
 * dispatch on it without ceremony.
 */
data object TopLevelQueryLabel : MetricFlowGraphLabel

/**
 * Label that identifies metric queries that do not have any sources — that is,
 * queries computing a base metric (simple, cumulative, conversion).
 *
 * Port of `metricflow.metric_evaluation.plan.me_labels.BaseMetricQueryLabel`.
 */
data object BaseMetricQueryLabel : MetricFlowGraphLabel
