package cc.monomer.metricflow.common.util

/**
 * Marker interface for the Visitor pattern.
 *
 * Port of `metricflow_semantics.toolkit.visitor.Visitable`. The Python class
 * is intentionally empty — it exists only to centralise a comment about the
 * pattern. We carry the same intent in Kotlin: implementing classes pair
 * with a separate `Visitor<R>` to dispatch on heterogeneous types.
 */
interface Visitable

/** Generic visitor that produces a value of type [R]. */
fun interface Visitor<in T : Visitable, out R> {
    fun visit(target: T): R
}
