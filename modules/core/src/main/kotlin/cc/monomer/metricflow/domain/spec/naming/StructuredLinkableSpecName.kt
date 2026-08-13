package cc.monomer.metricflow.domain.spec.naming

import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.naming.DUNDER
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference

/**
 * Parse a qualified group-by-item name into its structured parts.
 *
 * Port of `metricflow_semantics.naming.linkable_spec_name.StructuredLinkableSpecName`.
 *
 * Example: `listing__ds__week` parses as
 * - entityLinkNames: `[listing]`
 * - elementName: `ds`
 * - timeGranularityName: `week`
 *
 * This is a richer cousin of W1's
 * [cc.monomer.metricflow.domain.manifest.model.naming.StructuredDunderedName]:
 * - tracks a [date_part suffix][DatePart] (the part of a timestamp being extracted),
 * - distinguishes the inner-/outer-query entity link prefixes used by
 *   [cc.monomer.metricflow.domain.spec.GroupByMetricSpec],
 * - lowercases all string inputs (entity links, element name, granularity name).
 *
 * Names are case-folded on construction so callers cannot accidentally produce
 * two spec names that differ only in case.
 */
class StructuredLinkableSpecName(
    entityLinkNames: List<String>,
    elementName: String,
    timeGranularityName: String?,
    val datePart: DatePart?,
    val metricSubqueryEntityLinkNames: List<String>?,
) {

    /** Pre-lowercased entity link names. */
    val entityLinkNames: List<String> = entityLinkNames.map { it.lowercase() }

    /** Pre-lowercased element name. */
    val elementName: String = elementName.lowercase()

    /** Pre-lowercased time granularity name, or `null` when not constrained. */
    val timeGranularityName: String? = timeGranularityName?.lowercase()

    /**
     * Return the full dunder name. e.g. `ds` or `listing__ds__month`.
     *
     * If [datePart] is specified, granularity is dropped because it won't
     * impact the result.
     *
     * If [metricSubqueryEntityLinkNames] is specified, this represents a
     * group-by metric:
     * - inner and outer entity links are equal → use the standard form
     *   (`country__bookings`)
     * - otherwise both sets are appended in order
     *   (`listing__country__user__country__bookings`).
     */
    val dunderName: String
        get() {
            var entityLinks = entityLinkNames
            if (metricSubqueryEntityLinkNames != null) {
                if (entityLinkNames != metricSubqueryEntityLinkNames) {
                    entityLinks = entityLinkNames + metricSubqueryEntityLinkNames
                }
            }
            val items = entityLinks.toMutableList()
            items.add(elementName)
            if (datePart != null) {
                items.add(datePartSuffix(datePart))
            } else if (timeGranularityName != null) {
                items.add(timeGranularityName)
            }
            return items.joinToString(DUNDER)
        }

    /** Return the entity prefix. e.g. `listing__ds__month` -> `listing`. */
    val entityPrefix: String?
        get() = if (entityLinkNames.isEmpty()) null else entityLinkNames.joinToString(DUNDER)

    /** Returns the entity link references derived from the link names. */
    val entityLinks: List<EntityReference>
        get() = entityLinkNames.map { EntityReference(it) }

    /**
     * Renders the qualified name without the granularity suffix.
     *
     * Useful when the list-dimensions output has already de-duplicated time
     * dimensions by base grain.
     */
    val granularityFreeDunderName: String
        get() = StructuredLinkableSpecName(
            entityLinkNames = entityLinkNames,
            elementName = elementName,
            timeGranularityName = null,
            datePart = null,
            metricSubqueryEntityLinkNames = null,
        ).dunderName

    /** True iff the dunder name equals the bare element name. */
    val isElementName: Boolean get() = dunderName == elementName

    companion object {
        /**
         * Construct from a name e.g. `listing__ds__month`.
         *
         * Throws [IllegalArgumentException] when the last segment is an
         * `extract_*` date-part suffix — dunder syntax is reserved for
         * granularities only.
         */
        fun fromName(qualifiedName: String, customGranularityNames: List<String>): StructuredLinkableSpecName {
            val nameParts = qualifiedName.split(DUNDER)

            // No dunder, e.g. "ds"
            if (nameParts.size == 1) {
                return StructuredLinkableSpecName(
                    entityLinkNames = emptyList(),
                    elementName = nameParts[0],
                    timeGranularityName = null,
                    datePart = null,
                    metricSubqueryEntityLinkNames = null,
                )
            }

            for (datePart in DatePart.entries) {
                if (nameParts.last() == datePartSuffix(datePart)) {
                    throw IllegalArgumentException(
                        "Dunder syntax not supported for querying date_part. Use `group_by` object syntax instead.",
                    )
                }
            }

            var associatedGranularity: String? = null
            for (granularity in TimeGranularity.entries) {
                if (nameParts.last() == granularity.value) {
                    associatedGranularity = granularity.value
                    break
                }
            }
            if (associatedGranularity == null) {
                for (customGrain in customGranularityNames) {
                    if (nameParts.last() == customGrain) {
                        associatedGranularity = customGrain
                        break
                    }
                }
            }

            return if (associatedGranularity != null) {
                if (nameParts.size == 2) {
                    // e.g. "ds__month"
                    StructuredLinkableSpecName(
                        entityLinkNames = emptyList(),
                        elementName = nameParts[0],
                        timeGranularityName = associatedGranularity,
                        datePart = null,
                        metricSubqueryEntityLinkNames = null,
                    )
                } else {
                    // e.g. "messages__ds__month"
                    StructuredLinkableSpecName(
                        entityLinkNames = nameParts.dropLast(2),
                        elementName = nameParts[nameParts.size - 2],
                        timeGranularityName = associatedGranularity,
                        datePart = null,
                        metricSubqueryEntityLinkNames = null,
                    )
                }
            } else {
                // e.g. "messages__ds"
                StructuredLinkableSpecName(
                    entityLinkNames = nameParts.dropLast(1),
                    elementName = nameParts.last(),
                    timeGranularityName = null,
                    datePart = null,
                    metricSubqueryEntityLinkNames = null,
                )
            }
        }

        /** Suffix used for names with a date_part. */
        fun datePartSuffix(datePart: DatePart): String = "extract_${datePart.value}"
    }
}
