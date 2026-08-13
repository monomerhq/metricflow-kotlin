package cc.monomer.metricflow.integration.diff

import cc.monomer.metricflow.integration.diff.sqlnorm.SqlNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffRunnerSmoke {

    @Test
    fun `normalizer strips CRLF and trailing whitespace`() {
        val raw = "SELECT 1  \r\nFROM t   \r\n\r\n\r\n\r\n"
        val expected = "SELECT 1\nFROM t"
        assertEquals(expected, SqlNormalizer.normalize(raw))
    }

    @Test
    fun `normalizer collapses runs of blank lines to one`() {
        val raw = "a\n\n\n\nb\n"
        val expected = "a\n\nb"
        assertEquals(expected, SqlNormalizer.normalize(raw))
    }
}
