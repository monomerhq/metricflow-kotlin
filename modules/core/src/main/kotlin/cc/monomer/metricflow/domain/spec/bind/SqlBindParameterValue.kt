package cc.monomer.metricflow.domain.spec.bind

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The typed value carried by a [SqlBindParameter].
 *
 * Port of `metricflow_semantics.sql.sql_bind_parameters.SqlBindParameterValue`. The Python
 * version is a wide `@dataclass(frozen=True)` with six [Optional] fields and a
 * `__post_init__` that requires exactly one to be set; we encode the same closed set as
 * a `sealed interface` so the "exactly one set" invariant is statically enforced.
 *
 * Each variant carries the same payload type as the corresponding Python field:
 *
 * | Python field      | Kotlin variant              |
 * |-------------------|-----------------------------|
 * | `str_value`       | [StringValue]               |
 * | `int_value`       | [IntValue]                  |
 * | `float_value`     | [FloatValue]                |
 * | `datetime_value`  | [DateTimeValue]             |
 * | `date_value`      | [DateValue]                 |
 * | `bool_value`      | [BoolValue]                 |
 *
 * The [unionValue] property mirrors Python's `union_value` and returns the underlying
 * primitive boxed as `Any`. Renderers use [SqlColumnValue] (the typed wrapper) when they
 * want exhaustive dispatch.
 */
@Serializable
sealed interface SqlBindParameterValue {

    /** The underlying primitive — equivalent to Python's `union_value` property. */
    val unionValue: Any

    /** The typed wrapper for the underlying value. */
    val columnValue: SqlColumnValue

    @Serializable
    data class StringValue(val value: String) : SqlBindParameterValue {
        override val unionValue: Any get() = value
        override val columnValue: SqlColumnValue get() = SqlColumnValue.StringValue(value)
    }

    @Serializable
    data class IntValue(val value: Int) : SqlBindParameterValue {
        override val unionValue: Any get() = value
        override val columnValue: SqlColumnValue get() = SqlColumnValue.IntValue(value)
    }

    @Serializable
    data class FloatValue(val value: Double) : SqlBindParameterValue {
        override val unionValue: Any get() = value
        override val columnValue: SqlColumnValue get() = SqlColumnValue.FloatValue(value)
    }

    @Serializable
    data class DateTimeValue(@Serializable(LocalDateTimeIsoSerializer::class) val value: LocalDateTime) :
        SqlBindParameterValue {
        override val unionValue: Any get() = value
        override val columnValue: SqlColumnValue get() = SqlColumnValue.DateTimeValue(value)
    }

    @Serializable
    data class DateValue(@Serializable(LocalDateIsoSerializer::class) val value: LocalDate) :
        SqlBindParameterValue {
        override val unionValue: Any get() = value
        override val columnValue: SqlColumnValue get() = SqlColumnValue.DateValue(value)
    }

    @Serializable
    data class BoolValue(val value: Boolean) : SqlBindParameterValue {
        override val unionValue: Any get() = value
        override val columnValue: SqlColumnValue get() = SqlColumnValue.BoolValue(value)
    }

    companion object {
        /**
         * Lift a [SqlColumnValue] (or raw primitive via [SqlColumnValue.of]) into a
         * [SqlBindParameterValue].
         *
         * Port of `SqlBindParameterValue.create_from_sql_column_type`.
         */
        fun fromColumnValue(value: SqlColumnValue): SqlBindParameterValue = when (value) {
            is SqlColumnValue.StringValue -> StringValue(value.value)
            is SqlColumnValue.IntValue -> IntValue(value.value)
            is SqlColumnValue.FloatValue -> FloatValue(value.value)
            is SqlColumnValue.DateTimeValue -> DateTimeValue(value.value)
            is SqlColumnValue.DateValue -> DateValue(value.value)
            is SqlColumnValue.BoolValue -> BoolValue(value.value)
        }

        /** Build directly from a raw Kotlin primitive — mirrors Python's `create_from_sql_column_type`. */
        fun fromAny(value: Any): SqlBindParameterValue = fromColumnValue(SqlColumnValue.of(value))
    }
}
