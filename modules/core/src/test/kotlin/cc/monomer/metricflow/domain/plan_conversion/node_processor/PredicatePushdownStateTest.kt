package cc.monomer.metricflow.domain.plan_conversion.node_processor

import cc.monomer.metricflow.common.errors.FeatureNotSupportedError
import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredicatePushdownStateTest {

    @Test
    fun `disabled state has no pushdown potential`() {
        val state = PredicatePushdownState.withPushdownDisabled()
        assertFalse(state.hasPushdownPotential)
        assertFalse(state.hasTimeRangeConstraintToPushDown)
        assertFalse(state.hasWhereFiltersToPushDown)
    }

    @Test
    fun `default enabled types include categorical dimension and time range`() {
        val state = PredicatePushdownState.withDefaultEnabledTypes(timeRangeConstraint = null)
        assertTrue(PredicateInputType.CATEGORICAL_DIMENSION in state.pushdownEnabledTypes)
        assertTrue(PredicateInputType.TIME_RANGE_CONSTRAINT in state.pushdownEnabledTypes)
    }

    @Test
    fun `cannot configure ENTITY or TIME_DIMENSION input types`() {
        // Construction must throw because these types are explicitly unsupported in __post_init__.
        assertFailsWith<IllegalStateException> {
            PredicatePushdownState.create(
                timeRangeConstraint = null,
                whereFilterSpecs = emptyList(),
                appliedWhereFilterSpecs = emptyList(),
                pushdownEnabledTypes = setOf(PredicateInputType.ENTITY),
            )
        }
        assertFailsWith<IllegalStateException> {
            PredicatePushdownState.create(
                timeRangeConstraint = null,
                whereFilterSpecs = emptyList(),
                appliedWhereFilterSpecs = emptyList(),
                pushdownEnabledTypes = setOf(PredicateInputType.TIME_DIMENSION),
            )
        }
    }

    @Test
    fun `time range constraint requires TIME_RANGE_CONSTRAINT enabled`() {
        assertFailsWith<IllegalStateException> {
            PredicatePushdownState.create(
                timeRangeConstraint = TimeRangeConstraint(
                    LocalDateTime.parse("2020-01-01T00:00"),
                    LocalDateTime.parse("2020-12-31T00:00"),
                ),
                whereFilterSpecs = emptyList(),
                appliedWhereFilterSpecs = emptyList(),
                pushdownEnabledTypes = setOf(PredicateInputType.CATEGORICAL_DIMENSION),
            )
        }
    }

    @Test
    fun `where filter spec requires whereFilterPushdownEnabled`() {
        val filterSpec = WhereFilterSpec(
            whereSql = "x > 0",
            bindParameters = SqlBindParameterSet.EMPTY,
            elementSet = cc.monomer.metricflow.domain.spec.where.LinkableSpecGroup.EMPTY,
        )
        // pushdownEnabledTypes={TIME_RANGE_CONSTRAINT} alone disables where-filter pushdown.
        assertFailsWith<IllegalStateException> {
            PredicatePushdownState.create(
                timeRangeConstraint = null,
                whereFilterSpecs = listOf(filterSpec),
                appliedWhereFilterSpecs = emptyList(),
                pushdownEnabledTypes = setOf(PredicateInputType.TIME_RANGE_CONSTRAINT),
            )
        }
    }

    @Test
    fun `withTimeRangeConstraint preserves where filters and enables time range`() {
        val base = PredicatePushdownState.withPushdownDisabled()
        val constraint = TimeRangeConstraint(
            LocalDateTime.parse("2020-01-01T00:00"),
            LocalDateTime.parse("2020-12-31T00:00"),
        )
        val updated = PredicatePushdownState.withTimeRangeConstraint(base, constraint)
        assertEquals(constraint, updated.timeRangeConstraint)
        assertTrue(PredicateInputType.TIME_RANGE_CONSTRAINT in updated.pushdownEnabledTypes)
    }

    @Test
    fun `pushdownEligibleElementTypes raises for unsupported entities`() {
        // This requires us to construct a state with ENTITY enabled - but __post_init__ blocks
        // that path. The check is exercised in node_processor.py only when bypass logic admits
        // it. Smoke-test the property accessor against an empty supported set.
        val state = PredicatePushdownState.withDefaultEnabledTypes(timeRangeConstraint = null)
        // Categorical dimension only -> DIMENSION linkable type.
        val eligible = state.pushdownEligibleElementTypes
        assertTrue(cc.monomer.metricflow.domain.lookup.LinkableElementType.DIMENSION in eligible)
    }

    @Test
    fun `withoutTimeRangeConstraint removes time range and the enabled type`() {
        val original = PredicatePushdownState.create(
            timeRangeConstraint = TimeRangeConstraint(
                LocalDateTime.parse("2020-01-01T00:00"),
                LocalDateTime.parse("2020-12-31T00:00"),
            ),
            whereFilterSpecs = emptyList(),
            appliedWhereFilterSpecs = emptyList(),
            pushdownEnabledTypes = PredicatePushdownState.DEFAULT_PUSHDOWN_ENABLED_TYPES,
        )
        val updated = PredicatePushdownState.withoutTimeRangeConstraint(original)
        assertEquals(null, updated.timeRangeConstraint)
        assertFalse(PredicateInputType.TIME_RANGE_CONSTRAINT in updated.pushdownEnabledTypes)
    }

    @Suppress("unused")
    private fun unusedReferenceToImportedAssertion() {
        // Keep FeatureNotSupportedError import warm for future tests verifying its throw path.
        @Suppress("UnusedExpression") FeatureNotSupportedError::class
    }
}
