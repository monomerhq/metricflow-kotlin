package cc.monomer.metricflow.domain.manifest.model.naming

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference

/**
 * Group-by items (e.g. dimensions / entities) named with a double-underscore separator.
 *
 * Example: `listing__ds__week` parses as
 * - entityLinks: `[listing]`
 * - elementName: `ds`
 * - timeGranularity: `week`
 *
 * Port of `metricflow_semantic_interfaces/naming/dundered.py::StructuredDunderedName`.
 */
data class StructuredDunderedName(
    val entityLinks: List<EntityReference>,
    val elementName: String,
    val timeGranularity: String?,
) {

    /** Return the full name form. e.g. `ds` or `listing__ds__month`. */
    val dunderedName: String
        get() {
            val items = mutableListOf<String>()
            entityLinks.forEach { items.add(it.elementName) }
            items.add(elementName)
            if (timeGranularity != null) items.add(timeGranularity)
            return items.joinToString(DUNDER)
        }

    /** Return the name without the time granularity. e.g. `listing__ds__month` -> `listing__ds`. */
    val dunderedNameWithoutGranularity: String
        get() = (entityLinks.map { it.elementName } + elementName).joinToString(DUNDER)

    /** Return the name without the entity. e.g. `listing__ds__month` -> `ds__month`. */
    val dunderedNameWithoutEntity: String
        get() {
            val items = mutableListOf(elementName)
            if (timeGranularity != null) items.add(timeGranularity)
            return items.joinToString(DUNDER)
        }

    /** Return the entity prefix. e.g. `listing__ds__month` -> `listing`. */
    val entityPrefix: String?
        get() = if (entityLinks.isEmpty()) {
            null
        } else {
            entityLinks.joinToString(DUNDER) { it.elementName }
        }

    companion object {
        /** Construct from a string like `listing__ds__month`. */
        fun parseName(
            name: String,
            customGranularityNames: List<String>,
        ): StructuredDunderedName {
            val parts = name.split(DUNDER)
            // No dunder, e.g. "ds"
            if (parts.size == 1) {
                return StructuredDunderedName(emptyList(), parts[0], null)
            }
            val last = parts.last()
            var associatedGranularity: String? = null
            for (g in TimeGranularity.entries) {
                if (last == g.value) {
                    associatedGranularity = g.value
                    break
                }
            }
            if (associatedGranularity == null) {
                for (custom in customGranularityNames) {
                    if (last == custom) {
                        associatedGranularity = custom
                        break
                    }
                }
            }
            return if (associatedGranularity != null) {
                if (parts.size == 2) {
                    // e.g. "ds__month"
                    StructuredDunderedName(emptyList(), parts[0], associatedGranularity)
                } else {
                    // e.g. "messages__ds__month"
                    StructuredDunderedName(
                        entityLinks = parts.dropLast(2).map { EntityReference(it) },
                        elementName = parts[parts.size - 2],
                        timeGranularity = associatedGranularity,
                    )
                }
            } else {
                // e.g. "messages__ds"
                StructuredDunderedName(
                    entityLinks = parts.dropLast(1).map { EntityReference(it) },
                    elementName = parts.last(),
                    timeGranularity = null,
                )
            }
        }
    }
}
