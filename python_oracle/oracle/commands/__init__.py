"""One module per oracle subcommand.

Each module defines ``run(input_dict) -> dict`` that the CLI dispatcher calls.
"""

from oracle.commands import (
    entities_for_metrics,
    explain,
    explain_get_dimension_values,
    list_dimensions,
    list_group_bys,
    list_metrics,
    list_saved_queries,
    validate_manifest,
)

COMMANDS = {
    "explain": explain.run,
    "list_metrics": list_metrics.run,
    "list_dimensions": list_dimensions.run,
    "entities_for_metrics": entities_for_metrics.run,
    "list_group_bys": list_group_bys.run,
    "list_saved_queries": list_saved_queries.run,
    "explain_get_dimension_values": explain_get_dimension_values.run,
    "validate_manifest": validate_manifest.run,
}

__all__ = ["COMMANDS"]
