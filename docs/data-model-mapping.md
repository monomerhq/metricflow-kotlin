# Phase 0 — Pydantic → Kotlin data model mapping

How Python's `Protocol` + `PydanticBaseModel` + `Enum` patterns become Kotlin's `interface` + `@Serializable data class` + `enum class` / `sealed interface`.

## Mapping rules

1. **`pydantic.BaseModel` (frozen-ish via `HashableBaseModel`) → `data class` with `@Serializable`.** All fields go in the primary constructor. We do not use `companion object` factories at the manifest layer — these classes are deserialised straight from JSON.
2. **`Protocol[X]` → Kotlin `interface`.** When a single `data class` is the only implementation (the common case in metricflow), we **drop the protocol** and let the data class stand alone. The `Protocol` exists in Python because Pydantic and the protocol layer are two different libraries; in Kotlin's type system the data class structure already serves as the interface.
3. **`Enum` (subclassing `ExtendedEnum`) → `enum class`.** The `ExtendedEnum.values_set()` helper isn't needed — `enumValues<X>()` covers it.
4. **Closed Union (`Union[A, B, C]` where the cardinality is fixed) → `sealed interface` + `data class`/`data object` variants.** Examples: dataflow plan nodes, SQL expression nodes, validation issue severities.
5. **`Optional[X]` (= `X | None`) → `X?`.** No `Option` wrapper.
6. **ID-like dataclasses (`MetricReference`, `EntityReference`, `DimensionReference`, …) → `@JvmInline value class`.** Single-field reference types. Cheap. The Kotlin compiler still gives us type safety and equality.
7. **`Tuple[X, Y]` → `Pair<X, Y>` if anonymous; promote to a named `data class` if used in 2+ files.** Most metricflow code uses tuples for transient pairs (e.g. `(node_id, alias)`); we keep them as `Pair`.
8. **`@dataclass(frozen=True)` (NOT a `BaseModel` — used in `references.py`, `engine/models.py`, `dataflow/dataflow_plan.py`, `sql/...`) → plain `data class`.** Kotlin data classes are already structural-equality + immutable. No `@Serializable` unless the type crosses a serialisation boundary (manifest/spec do; dataflow/SQL plan don't unless we serialise plans).
9. **`Field(default_factory=list)` → `= emptyList()` in the Kotlin constructor signature, but only at use-points where the call site cannot pass `null`. CLAUDE.md says "no default parameter values" — exception applies to `MetricFlowQueryRequest`-style entry types where Python explicitly defaults things; everywhere else, callers pass explicit empties.**
10. **`Sequence[X]` → `List<X>`.** Kotlin doesn't distinguish; metricflow uses `Sequence` to mean "read-only iterable".
11. **`Set[X]` → `Set<X>`.** Use `LinkedHashSet` when the order is observable in tests.
12. **`Mapping[K, V]` → `Map<K, V>`.** When the implementation is mutated internally, the field is `MutableMap`; the public type stays `Map`.
13. **`@property` (Python computed property) → Kotlin computed property (`val x: X get() = …`)** or a top-level `fun` if the result depends on more than `this`.

## Five worked examples

### Example 1 — `PydanticMetric` (closed enum, optional fields, nested types) → `Metric`

Source: [`metricflow_semantic_interfaces/implementations/metric.py:232-247`](../python_oracle/upstream/metricflow_semantic_interfaces/implementations/metric.py).

**Python (Pydantic):**

```python
class PydanticMetric(HashableBaseModel, ModelWithMetadataParsing, ProtocolHint[Metric]):
    """Describes a metric."""

    name: str
    description: Optional[str]
    type: MetricType
    type_params: PydanticMetricTypeParams
    filter: Optional[PydanticWhereFilterIntersection]
    metadata: Optional[PydanticMetadata]
    label: Optional[str] = None
    config: Optional[PydanticSemanticLayerElementConfig]
    time_granularity: Optional[str] = None
```

**Kotlin:**

```kotlin
package cc.monomer.metricflow.domain.manifest.model

@Serializable
data class Metric(
    val name: String,
    val description: String?,
    val type: MetricType,
    val typeParams: MetricTypeParams,
    val filter: WhereFilterIntersection?,
    val metadata: Metadata?,
    val label: String?,
    val config: SemanticLayerElementConfig?,
    val timeGranularity: String?,
) {
    /** All input measures for this metric, derived from typeParams. See [PydanticMetric.input_measures]. */
    val inputMeasures: List<MetricInputMeasure>
        get() = typeParams.inputMeasures
}
```

Key transforms:

- Drop `Pydantic` prefix; merge with the `Metric` protocol (one type, not two).
- `description: Optional[str]` (no default) → `description: String?` (no default — caller must pass `null` explicitly).
- `MetricType` enum stays as enum (see Example 3 below).
- `@property input_measures` → Kotlin computed property with KDoc citing the Python source.

### Example 2 — `PydanticDimension` (Optional with nested) → `Dimension`

Source: [`metricflow_semantic_interfaces/implementations/elements/dimension.py:43-54`](../python_oracle/upstream/metricflow_semantic_interfaces/implementations/elements/dimension.py).

**Python:**

```python
class PydanticDimension(HashableBaseModel, ModelWithMetadataParsing):
    """Describes a dimension."""

    name: str
    description: Optional[str]
    type: DimensionType
    is_partition: bool = False
    type_params: Optional[PydanticDimensionTypeParams]
    expr: Optional[str] = None
    metadata: Optional[PydanticMetadata]
    label: Optional[str] = None
    config: Optional[PydanticSemanticLayerElementConfig]
```

**Kotlin:**

```kotlin
@Serializable
data class Dimension(
    val name: String,
    val description: String?,
    val type: DimensionType,
    val isPartition: Boolean,            // no default; caller passes false
    val typeParams: DimensionTypeParams?,
    val expr: String?,
    val metadata: Metadata?,
    val label: String?,
    val config: SemanticLayerElementConfig?,
) {
    /** Reference type used for lookup tables. */
    val reference: DimensionReference get() = DimensionReference(name)

    /** Non-null iff this is a TIME dimension. */
    val timeDimensionReference: TimeDimensionReference?
        get() = if (type == DimensionType.TIME) TimeDimensionReference(name) else null
}
```

Key transforms:

- `is_partition: bool = False` loses its default (CLAUDE.md "Explicit Code"). The same Pydantic pattern means *"if absent in JSON, default to false"* — that becomes a kotlinx-serialization `@SerialName` + a manually-written deserializer if needed, OR (more idiomatic) the JSON normaliser fills it in before reaching Kotlin.
- The Python `@property reference` is unconditionally a `DimensionReference`; in Kotlin it's a non-nullable computed val.
- `time_dimension_reference` is conditional → returns `TimeDimensionReference?`.

### Example 3 — `MetricType` (enum) → enum class

Source: [`metricflow_semantic_interfaces/type_enums/metric_type.py`](../python_oracle/upstream/metricflow_semantic_interfaces/type_enums/metric_type.py).

**Python:**

```python
class MetricType(ExtendedEnum):
    """Currently supported metric types."""

    SIMPLE = "simple"
    RATIO = "ratio"
    CUMULATIVE = "cumulative"
    DERIVED = "derived"
    CONVERSION = "conversion"
```

**Kotlin:**

```kotlin
package cc.monomer.metricflow.domain.manifest.model.enums

@Serializable
enum class MetricType(val value: String) {
    @SerialName("simple")     SIMPLE("simple"),
    @SerialName("ratio")      RATIO("ratio"),
    @SerialName("cumulative") CUMULATIVE("cumulative"),
    @SerialName("derived")    DERIVED("derived"),
    @SerialName("conversion") CONVERSION("conversion"),
    ;
}
```

Or, simpler when the JSON is always lower-case-of-name, omit `@SerialName` and rely on `JsonNamingStrategy.SnakeCase` / explicit name → enum mapping at the boundary. Either way, it's a one-to-one mapping.

The Python helper `assert_values_exhausted(metric_type)` becomes Kotlin's exhaustive `when` (compile-time guarantee).

### Example 4 — `MetricReference` family (ID dataclasses) → `value class` hierarchy

Source: [`metricflow_semantic_interfaces/references.py:1-58`](../python_oracle/upstream/metricflow_semantic_interfaces/references.py).

Python uses dataclass inheritance to model a "kind" relationship: `ElementReference ← LinkableElementReference ← DimensionReference ← TimeDimensionReference`, and similarly for entity, measure, metric.

**Python (excerpt):**

```python
@dataclass(frozen=True, order=True)
class ElementReference(SerializableDataclass):
    element_name: str

@dataclass(frozen=True, order=True)
class MeasureReference(ElementReference):
    pass

@dataclass(frozen=True, order=True)
class DimensionReference(LinkableElementReference):
    @property
    def time_dimension_reference(self) -> TimeDimensionReference:
        return TimeDimensionReference(element_name=self.element_name)
```

**Kotlin:**

The single-field, immutable, hashable reference types are *exactly* the `@JvmInline value class` use case. We lose the inheritance chain (Kotlin value classes can't extend) but gain zero-allocation runtime cost and accidental-mix prevention.

```kotlin
package cc.monomer.metricflow.domain.manifest.model

@JvmInline @Serializable
value class MeasureReference(val elementName: String) : Comparable<MeasureReference> {
    override fun compareTo(other: MeasureReference) = elementName.compareTo(other.elementName)
}

@JvmInline @Serializable
value class DimensionReference(val elementName: String) : Comparable<DimensionReference> {
    val timeDimensionReference: TimeDimensionReference get() = TimeDimensionReference(elementName)
    override fun compareTo(other: DimensionReference) = elementName.compareTo(other.elementName)
}

@JvmInline @Serializable
value class TimeDimensionReference(val elementName: String) : Comparable<TimeDimensionReference> {
    val dimensionReference: DimensionReference get() = DimensionReference(elementName)
    override fun compareTo(other: TimeDimensionReference) = elementName.compareTo(other.elementName)
}

@JvmInline @Serializable
value class EntityReference(val elementName: String) : Comparable<EntityReference> {
    override fun compareTo(other: EntityReference) = elementName.compareTo(other.elementName)
}

@JvmInline @Serializable
value class MetricReference(val elementName: String) : Comparable<MetricReference> {
    override fun compareTo(other: MetricReference) = elementName.compareTo(other.elementName)
}
```

Where Python relied on the inheritance chain (e.g. "all `LinkableElementReference`s") — in Kotlin we restate that with a `sealed interface LinkableElementReference` that the affected value classes implement (note: value classes can implement interfaces). Some references that are never used polymorphically can skip the interface entirely.

The `LinkableElementReference` ↔ `DimensionReference` ↔ `EntityReference` polymorphism in Python is mostly a marker: the only place we type something as the parent is in collections that hold mixed kinds. Kotlin uses the `sealed interface`:

```kotlin
sealed interface LinkableElementReference : Comparable<LinkableElementReference> {
    val elementName: String
}
```

with the value classes adding `: LinkableElementReference` where appropriate.

### Example 5 — `MetricInputMeasure` (Pydantic + Protocol with custom parser) → `data class`

Source: [`metricflow_semantic_interfaces/protocols/metric.py:23-69`](../python_oracle/upstream/metricflow_semantic_interfaces/protocols/metric.py) and [`metricflow_semantic_interfaces/implementations/metric.py:39-78`](../python_oracle/upstream/metricflow_semantic_interfaces/implementations/metric.py).

The Python pattern: `Protocol` declares property accessors, `PydanticCustomInputParser._from_yaml_value` provides a "from string" constructor for backwards-compat YAML.

**Python (Pydantic implementation):**

```python
class PydanticMetricInputMeasure(PydanticCustomInputParser, HashableBaseModel):
    name: str
    filter: Optional[PydanticWhereFilterIntersection]
    alias: Optional[str]
    join_to_timespine: bool = False
    fill_nulls_with: Optional[int] = None

    @classmethod
    def _from_yaml_value(cls, input):
        if isinstance(input, str):
            return PydanticMetricInputMeasure(name=input)
        else:
            raise ValueError(...)
    @property
    def measure_reference(self) -> MeasureReference:
        return MeasureReference(element_name=self.name)
```

**Kotlin:**

```kotlin
@Serializable
data class MetricInputMeasure(
    val name: String,
    val filter: WhereFilterIntersection?,
    val alias: String?,
    val joinToTimespine: Boolean,
    val fillNullsWith: Int?,
) {
    val measureReference: MeasureReference get() = MeasureReference(name)
    val postAggregationMeasureReference: MeasureReference get() = MeasureReference(alias ?: name)

    companion object {
        /**
         * Backwards-compat: legacy YAML configs allowed a bare string in place of an object.
         * Used only by the YAML test fixtures. Production hydration goes through kotlinx-serialization.
         */
        fun fromString(name: String): MetricInputMeasure = MetricInputMeasure(
            name = name, filter = null, alias = null,
            joinToTimespine = false, fillNullsWith = null,
        )
    }
}
```

Key transforms:

- Single class; no separate `Protocol` (the original `MetricInputMeasure` Protocol is a 1:1 of the same fields — drop it).
- `_from_yaml_value` becomes a named `companion object` factory `fromString`. Kotlin's serialization isn't responsible for "string → object" coercion, so we do it explicitly at the parsing edge.
- `join_to_timespine: bool = False` → `joinToTimespine: Boolean` (CLAUDE.md no-defaults; the JSON normaliser fills `false` if absent).
- Computed properties stay computed.

## Special cases

### `dataflow_plan` nodes — `sealed interface` family

`metricflow/dataflow/nodes/*.py` defines 22 dataflow node types, all extending a `DataflowPlanNode` ABC and implementing a visitor pattern (`accept`). In Kotlin:

```kotlin
sealed interface DataflowPlanNode {
    val nodeId: NodeId
    val parents: List<DataflowPlanNode>
    fun <T> accept(visitor: DataflowPlanNodeVisitor<T>): T
}

data class JoinOnEntitiesNode(...) : DataflowPlanNode { override fun <T> accept(v) = v.visitJoinOnEntitiesNode(this) }
data class ComputeMetricsNode(...) : DataflowPlanNode { override fun <T> accept(v) = v.visitComputeMetricsNode(this) }
// … 20 more
```

The visitor pattern is preserved verbatim; Kotlin's exhaustive `when` over the sealed interface is an *additional* idiomatic choice (some sites in Kotlin will use it instead of the visitor when there are no subclass-specific recursion needs).

### `SqlPlanNode` family — same treatment as dataflow nodes

`metricflow/sql/sql_*.py` defines `SqlPlanNode` and subclasses. Identical sealed-interface pattern.

### `ValidationIssue` (warning vs error vs future-error)

```kotlin
sealed interface ValidationIssue {
    val message: String
    val context: ValidationIssueContext?
    val extraDetails: String?
}
data class ValidationWarning(...) : ValidationIssue
data class ValidationFutureError(...) : ValidationIssue
data class ValidationError(...) : ValidationIssue
```

The Python `ValidationIssueLevel` enum disappears: the type itself encodes the level.

### `Sequence[Sequence[X]]` (rule pipeline ordering) — `List<List<X>>`

Used in `SemanticManifestTransformer` for "primary then secondary rule sequences". Direct mapping.

### `ProtocolHint[X]` — drop entirely

`ProtocolHint[X]` is a Pydantic-Protocol marriage trick that has no analogue in Kotlin (and no behaviour). Drop the marker; the Kotlin data class implements its own interface trivially.

### `HashableBaseModel`, `ModelWithMetadataParsing` — drop entirely

Pydantic helpers for hashable-by-value plus a hook for metadata parsing. Kotlin `data class` is hashable by value out of the box; metadata parsing is a no-op or moves to `init {}`.

### `assert_values_exhausted(x)` — Kotlin exhaustive `when`

```kotlin
val name = when (metric.type) {
    MetricType.SIMPLE     -> ...
    MetricType.RATIO      -> ...
    MetricType.CUMULATIVE -> ...
    MetricType.DERIVED    -> ...
    MetricType.CONVERSION -> ...
}
```

The Kotlin compiler enforces exhaustiveness; if `MetricType` ever gains a new variant, every `when` over it fails to compile until updated.

## Serialization at the boundary

Per CLAUDE.md, we use **kotlinx-serialization** (not Jackson). All `domain.manifest.model.*` data classes are `@Serializable`. Engine entry points accept JSON strings (mimicking `engine_wrapper.py`'s `semantic_models_json: Sequence[str]`); the JSON is decoded with `Json { ignoreUnknownKeys = true }` to tolerate dbt manifest extensions.

The `DefaultJsonNormaliser` (a small kotlinx-serialization plugin) is responsible for filling Pydantic-style absent-field defaults *before* deserialisation, so the data classes themselves do not need default parameter values. This keeps CLAUDE.md's "no default parameters" rule clean while still accepting Pydantic-shaped JSON.
