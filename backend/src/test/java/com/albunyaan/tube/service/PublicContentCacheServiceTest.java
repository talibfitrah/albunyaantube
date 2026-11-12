package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Stage 9 B13: every public-content eviction (every moderation write calls one) announces itself to the live view. */
class PublicContentCacheServiceTest {

    @Test
    void anEvictionAnnouncesItself() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        new PublicContentCacheService(events).evictPublicContentCaches();

        verify(events).publishEvent(any(PublicContentCacheService.Evicted.class));
    }

    @Test
    void aContentEvictionAnnouncesAContentChange() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        new PublicContentCacheService(events).evictPublicContentCaches();
        verify(events).publishEvent(any(PublicContentCacheService.ContentChanged.class));
    }

    @Test
    void aLiveEvictionAnnouncesItselfButNoContentChange() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        new PublicContentCacheService(events).evictForLiveChange();
        verify(events).publishEvent(any(PublicContentCacheService.Evicted.class));
        verify(events, never()).publishEvent(any(PublicContentCacheService.ContentChanged.class));
    }
}
