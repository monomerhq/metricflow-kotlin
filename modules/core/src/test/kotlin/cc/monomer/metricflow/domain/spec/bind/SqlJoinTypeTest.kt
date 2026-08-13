package cc.monomer.metricflow.domain.spec.bind

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlJoinTypeTest {

    @Test
    fun `every join type carries the rendered SQL fragment from python`() {
        assertEquals("LEFT OUTER JOIN", SqlJoinType.LEFT_OUTER.sql)
        assertEquals("FULL OUTER JOIN", SqlJoinType.FULL_OUTER.sql)
        assertEquals("INNER JOIN", SqlJoinType.INNER.sql)
        assertEquals("CROSS JOIN", SqlJoinType.CROSS_JOIN.sql)
    }

    @Test
    fun `toString uses python-style repr form`() {
        assertEquals("SqlJoinType.INNER", SqlJoinType.INNER.toString())
    }
}
