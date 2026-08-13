package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.spec.where.LinkableSpecGroup
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhereFilterSpecElementSetTest {

    @Test
    fun `WhereFilterSpec exposes the elementSet through linkableSpecs`() {
        val empty = WhereFilterSpec(
            whereSql = "1=1",
            bindParameters = SqlBindParameterSet(),
            elementSet = GroupByItemSet.EMPTY,
        )
        assertTrue(empty.linkableSpecs.isEmpty())
        assertTrue(empty.elementSet.isEmpty)
    }

    @Test
    fun `WhereFilterSpec fromLinkableSpecs wraps a bare list`() {
        val dimSpec = DimensionSpec(
            elementName = "country",
            entityLinks = emptyList(),
            alias = null,
        )
        val spec = WhereFilterSpec.fromLinkableSpecs(
            whereSql = "country = 'US'",
            bindParameters = SqlBindParameterSet(),
            linkableSpecs = listOf(dimSpec),
        )
        assertEquals(1, spec.linkableSpecs.size)
        assertEquals(dimSpec, spec.linkableSpecs[0])
    }

    @Test
    fun `LinkableSpecGroup EMPTY is reusable`() {
        assertTrue(LinkableSpecGroup.EMPTY.isEmpty)
        assertTrue(LinkableSpecGroup.EMPTY.specs.isEmpty())
    }
}
