package cc.monomer.metricflow.domain.spec.bind

import cc.monomer.metricflow.common.util.Mergeable
import kotlinx.serialization.Serializable

/**
 * An ordered collection of [SqlBindParameter]s, used as the runtime-bound execution parameter
 * set when rendering a SQL plan.
 *
 * Port of `metricflow_semantics.sql.sql_bind_parameters.SqlBindParameterSet`.
 *
 * The Python class is `Mergeable` and uses an internal tuple of `(key, value)` items. We
 * preserve those semantics:
 *
 * - Iteration order matches insertion order ([paramItems] is a `List` not a `Set`).
 * - [merge] raises if the same key appears with conflicting values in `this` and `other`.
 * - Equality compares by key→value mapping (Python overrides `__eq__` for the same reason).
 *
 * The Python `param_dict` property (`OrderedDict[str, SqlColumnType]`) is mirrored by
 * [paramDict].
 */
@Serializable
data class SqlBindParameterSet(
    val paramItems: List<SqlBindParameter> = emptyList(),
) : Mergeable<SqlBindParameterSet> {

    /**
     * Merge another set, preserving "left wins on first occurrence" iteration order.
     *
     * Port of `SqlBindParameterSet.merge`. Throws if `key ∈ this AND key ∈ other AND
     * this[key] != other[key]`.
     */
    override fun merge(other: SqlBindParameterSet): SqlBindParameterSet {
        if (paramItems.isEmpty()) return other
        if (other.paramItems.isEmpty()) return this

        val selfMap = paramItems.associate { it.key to it.value }
        val otherMap = other.paramItems.associate { it.key to it.value }

        for ((key, value) in otherMap) {
            val existing = selfMap[key]
            if (existing != null && existing != value) {
                throw IllegalStateException(
                    "Conflict with key $key in combining parameters. " +
                        "Existing params: $selfMap Additional params: $otherMap",
                )
            }
        }

        val includedKeys = paramItems.mapTo(mutableSetOf()) { it.key }
        val merged = paramItems.toMutableList()
        for (item in other.paramItems) {
            if (includedKeys.add(item.key)) {
                merged.add(item)
            }
        }
        return SqlBindParameterSet(merged)
    }

    /** Useful for passing into SQL-driver methods. Keys appear in insertion order. */
    val paramDict: Map<String, SqlColumnValue>
        get() = LinkedHashMap<String, SqlColumnValue>().also { acc ->
            for (item in paramItems) {
                acc[item.key] = item.value.columnValue
            }
        }

    override fun equals(other: Any?): Boolean {
        if (other !is SqlBindParameterSet) return false
        return paramDict == other.paramDict
    }

    override fun hashCode(): Int = paramDict.hashCode()

    companion object {
        /** Port of `SqlBindParameterSet.empty_instance` — the [Mergeable] identity element. */
        val EMPTY: SqlBindParameterSet = SqlBindParameterSet(emptyList())

        /** Port of `SqlBindParameterSet.create_from_dict`. */
        fun createFromDict(paramDict: Map<String, SqlColumnValue>): SqlBindParameterSet =
            SqlBindParameterSet(
                paramDict.map { (k, v) ->
                    SqlBindParameter(key = k, value = SqlBindParameterValue.fromColumnValue(v))
                },
            )

        /** Lift raw Kotlin primitives into a bind-parameter set. */
        fun createFromAnyDict(paramDict: Map<String, Any>): SqlBindParameterSet =
            SqlBindParameterSet(
                paramDict.map { (k, v) ->
                    SqlBindParameter(key = k, value = SqlBindParameterValue.fromAny(v))
                },
            )
    }
}
