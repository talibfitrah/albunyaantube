package com.albunyaan.tube.player

import androidx.media3.datasource.DefaultHttpDataSource
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CronetDataSourceFactoryTest {

    private lateinit var factory: CronetDataSourceFactory

    @Before
    fun setUp() {
        // Robolectric has no Cronet providers → engine will be null → fallback expected.
        factory = CronetDataSourceFactory(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `createForAndroidUA returns DefaultHttpDataSource factory when Cronet unavailable`() {
        val dsFactory = factory.createForAndroidUA()
        assertTrue(
            "Expected DefaultHttpDataSource.Factory when Cronet unavailable",
            dsFactory is DefaultHttpDataSource.Factory
        )
    }

    @Test
    fun `createForIosUA returns DefaultHttpDataSource factory when Cronet unavailable`() {
        val dsFactory = factory.createForIosUA()
        assertTrue(
            "Expected DefaultHttpDataSource.Factory when Cronet unavailable",
            dsFactory is DefaultHttpDataSource.Factory
        )
    }
}
