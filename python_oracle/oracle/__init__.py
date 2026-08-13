"""Python-side oracle CLI for the metricflow-kotlin differential test harness.

This package wraps metricflow 0.210.0 so that Kotlin (or any other caller) can
invoke each of the 8 entry points exposed by :class:`MetricFlowEngine` /
:class:`SemanticManifestValidator` through a JSON-in / JSON-out command-line
interface.

The 8 subcommands (one per Kotlin port-scope entry point):

    explain                       - query -> SQL string (via MetricFlowEngine.explain)
    list_metrics                  - metric catalog
    list_dimensions               - dimensions (per-metric or whole manifest)
    entities_for_metrics          - entities common to a metric set
    list_group_bys                - dimensions + entities for a metric set
    list_saved_queries            - saved query catalog
    explain_get_dimension_values  - dimension-value SQL string
    validate_manifest             - SemanticManifestValidator issues

All subcommands read JSON from stdin and write JSON to stdout. Errors go to
stderr with a non-zero exit code. The CLI is the **oracle** for Phase 1b's
corpus extractor and Phase 3's Kotlin diff runner.
"""

__all__ = ["__version__"]

__version__ = "0.1.0"
