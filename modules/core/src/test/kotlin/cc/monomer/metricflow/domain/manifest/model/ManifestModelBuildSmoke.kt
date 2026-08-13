package cc.monomer.metricflow.domain.manifest.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestModelBuildSmoke {

    @Test
    fun `module is wired in the gradle build`() {
        assertEquals(":domain:manifest:model", ManifestModelModule.GRADLE_PATH)
    }
}
