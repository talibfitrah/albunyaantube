package com.albunyaan.tube.player

import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import kotlinx.coroutines.runBlocking
import com.albunyaan.tube.data.extractor.ExtractionClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SegmentPreBufferTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var cache: SimpleCache
    private lateinit var segmentPreBuffer: SegmentPreBuffer

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val dbProvider = StandaloneDatabaseProvider(context)
        cache = SimpleCache(tmpFolder.newFolder("cache"), NoOpCacheEvictor(), dbProvider)
        segmentPreBuffer = SegmentPreBuffer(
            context,
            cache,
            SegmentDataSourceFactoryProvider(context, CronetDataSourceFactory(context), cache),
        )
    }

    @After
    fun tearDown() {
        cache.release()
    }

    @Test
    fun `preBuffer swallows IOException without throwing`() = runBlocking {
        // A non-existent URL will cause IOException — must not propagate
        segmentPreBuffer.preBuffer(
            "http://localhost:9999/no-such-url",
            ExtractionClient.NEWPIPE_ANDROID,
            durationMs = 1000,
        )
        // Reaching here means no exception was thrown
    }

    @Test
    fun `preBuffer swallows all errors without throwing`() = runBlocking {
        segmentPreBuffer.preBuffer("", ExtractionClient.NEWPIPE_ANDROID, durationMs = 500)
        segmentPreBuffer.preBuffer("not-a-url", ExtractionClient.NEWPIPE_IOS, durationMs = 500)
    }
}
