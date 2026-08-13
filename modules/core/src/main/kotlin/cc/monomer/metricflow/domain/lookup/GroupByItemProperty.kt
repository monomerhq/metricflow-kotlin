package cc.monomer.metricflow.domain.lookup

/**
 * The properties associated with the items in a `BaseGroupByItemSet`.
 *
 * Port of `metricflow_semantics/model/linkable_element_property.py::GroupByItemProperty`.
 *
 * "Local" means an element that is defined within the same semantic model as the measure.
 * This definition is used throughout the related classes.
 */
enum class GroupByItemProperty(val value: String) {
    /** A local element (defined within the same semantic model as the measure). */
    LOCAL("local"),

    /** A local dimension that is prefixed with a local primary entity. */
    LOCAL_LINKED("local_linked"),

    /** An element that was joined to the measure semantic model by an entity. */
    JOINED("joined"),

    /** An element that was joined to the measure semantic model by joining multiple semantic models. */
    MULTI_HOP("multi_hop"),

    /** A time dimension that is a version of a time dimension in a semantic model, but at a different granularity. */
    DERIVED_TIME_GRANULARITY("derived_time_granularity"),

    /** Refers to an entity, not a dimension. */
    ENTITY("entity"),

    /** See `metric_time` in `DataSet`. */
    METRIC_TIME("metric_time"),

    /** Refers to a metric, not a dimension. */
    METRIC("metric"),

    /** A time dimension with a `DatePart`. */
    DATE_PART("date_part"),
    ;

    companion object {
        /** Returns the set of all defined properties. */
        fun allProperties(): Set<GroupByItemProperty> = entries.toSet()
    }
}
