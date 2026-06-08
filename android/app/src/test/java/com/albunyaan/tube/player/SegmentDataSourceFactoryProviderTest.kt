package com.albunyaan.tube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.SimpleCache
import com.albunyaan.tube.data.extractor.ExtractionClient
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(UnstableApi::class)
class SegmentDataSourceFactoryProviderTest {

    private lateinit var context: Context
    private lateinit var cronetDataSourceFactory: CronetDataSourceFactory
    private lateinit var simpleCache: SimpleCache
    private lateinit var provider: SegmentDataSourceFactoryProvider

    private val stubFactory: DataSource.Factory = mock()

    @Before
    fun setUp() {
        context = mock()
        // Return a non-null application context to satisfy DefaultDataSource.Factory
        whenever(context.applicationContext).thenReturn(context)

        cronetDataSourceFactory = mock()
        simpleCache = mock(defaultAnswer = org.mockito.Mockito.RETURNS_DEEP_STUBS)

        whenever(cronetDataSourceFactory.createForIosUA()).thenReturn(stubFactory)
        whenever(cronetDataSourceFactory.createForAndroidUA()).thenReturn(stubFactory)

        provider = SegmentDataSourceFactoryProvider(context, cronetDataSourceFactory, simpleCache)
    }

    @Test
    fun `forClient NEWPIPE_IOS invokes createForIosUA`() {
        provider.forClient(ExtractionClient.NEWPIPE_IOS)
        verify(cronetDataSourceFactory).createForIosUA()
    }

    @Test
    fun `forClient ANDROID_VR invokes createForAndroidUA`() {
        provider.forClient(ExtractionClient.ANDROID_VR)
        verify(cronetDataSourceFactory).createForAndroidUA()
    }

    @Test
    fun `forClient NEWPIPE_ANDROID invokes createForAndroidUA`() {
        provider.forClient(ExtractionClient.NEWPIPE_ANDROID)
        verify(cronetDataSourceFactory).createForAndroidUA()
    }
}
