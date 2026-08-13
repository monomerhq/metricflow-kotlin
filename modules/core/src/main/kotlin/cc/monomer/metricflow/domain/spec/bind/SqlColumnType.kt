package cc.monomer.metricflow.domain.spec.bind

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Marker for the closed set of types that can flow through a SQL bind parameter.
 *
 * Port of `metricflow_semantics.sql.sql_column_type.SqlColumnType` (Python type alias
 * `Union[str, int, float, datetime.datetime, datetime.date, bool]`).
 *
 * Kotlin lacks anonymous unions, so we encode the same closed set as a `sealed interface`
 * with one wrapper per primitive. The wrappers are zero-cost — they wrap a primitive
 * (or [LocalDate]/[LocalDateTime]) and expose it via [SqlColumnValue.raw]. Consumers
 * match exhaustively over the sealed family or call [raw] for `Any` dispatch.
 *
 * In practice the only callers are [SqlBindParameterValue] and the dialect renderers
 * (W6); both operate via [raw] so the wrapper boxing is acceptable.
 */
sealed interface SqlColumnValue {
    /** The underlying primitive — `String`, `Int`, `Double`, [LocalDateTime], [LocalDate] or `Boolean`. */
    val raw: Any

    data class StringValue(val value: String) : SqlColumnValue {
        override val raw: Any get() = value
    }

    data class IntValue(val value: Int) : SqlColumnValue {
        override val raw: Any get() = value
    }

    data class FloatValue(val value: Double) : SqlColumnValue {
        override val raw: Any get() = value
    }

    data class DateTimeValue(val value: LocalDateTime) : SqlColumnValue {
        override val raw: Any get() = value
    }

    data class DateValue(val value: LocalDate) : SqlColumnValue {
        override val raw: Any get() = value
    }

    data class BoolValue(val value: Boolean) : SqlColumnValue {
        override val raw: Any get() = value
    }

    companion object {
        /**
         * Lift a raw Kotlin primitive into the closed [SqlColumnValue] family.
         *
         * Port of `SqlBindParameterValue.create_from_sql_column_type`. Matches Python's
         * isinstance ladder; throws when the input isn't a supported [SqlColumnType].
         */
        fun of(value: Any): SqlColumnValue = when (value) {
            is Boolean -> BoolValue(value)
            is Int -> IntValue(value)
            is Long -> IntValue(value.toInt())
            is Double -> FloatValue(value)
            is Float -> FloatValue(value.toDouble())
            is String -> StringValue(value)
            is LocalDateTime -> DateTimeValue(value)
            is LocalDate -> DateValue(value)
            else -> throw IllegalArgumentException(
                "Unhandled type for SqlColumnValue: ${value::class.qualifiedName}",
            )
        }
    }
}
