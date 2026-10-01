package com.filamentvision.backend

import com.filamentvision.core.CoreMarker
import org.junit.Assert.assertSame
import org.junit.Test

class BackendSmokeTest {
    @Test
    fun backendCanReferenceCoreAtCompileAndRuntime() {
        assertSame(CoreMarker, CoreMarker)
    }
}
