/**
 * Test data builders for creating mock data in tests.
 *
 * Usage:
 * ```ts
 * import { createMockVideo, createMockChannel } from '../utils/testData';
 *
 * const video = createMockVideo({ title: 'My Test Video' });
 * ```
 */

export function createMockVideo(overrides: Partial<any> = {}) {
  return {
    id: `video_${Date.now()}`,
    title: 'Test Video',
    channelId: 'test_channel',
    channelName: 'Test Channel',
    thumbnailUrl: 'https://example.com/thumb.jpg',
    durationSeconds: 600,
    viewCount: 1000,
    uploadedAt: new Date().toISOString(),
    description: 'Test video description',
    categoryId: 'quran',
    status: 'APPROVED',
    ...overrides
  };
}

export function createMockChannel(overrides: Partial<any> = {}) {
  return {
    id: `channel_${Date.now()}`,
    name: 'Test Channel',
    thumbnailUrl: 'https://example.com/channel.jpg',
    subscriberCount: 10000,
    videoCount: 50,
    description: 'Test channel description',
    categoryId: 'quran',
    status: 'APPROVED',
    ...overrides
  };
}

export function createMockPlaylist(overrides: Partial<any> = {}) {
  return {
    id: `playlist_${Date.now()}`,
    title: 'Test Playlist',
    channelId: 'test_channel',
    channelName: 'Test Channel',
    thumbnailUrl: 'https://example.com/playlist.jpg',
    itemCount: 20,
    description: 'Test playlist description',
    categoryId: 'quran',
    status: 'APPROVED',
    ...overrides
  };
}

export function createMockCategory(overrides: Partial<any> = {}) {
  return {
    id: `category_${Date.now()}`,
    name: 'Test Category',
    description: 'Test category description',
    parentCategoryId: null,
    itemCount: 100,
    ...overrides
  };
}

export function createMockApproval(overrides: Partial<any> = {}) {
  return {
    id: `approval_${Date.now()}`,
    type: 'CHANNEL',
    entityId: 'channel_123',
    title: 'Test Channel',
    category: 'Quran',
    submittedAt: new Date().toISOString(),
    submittedBy: 'admin@example.com',
    metadata: {
      subscriberCount: '1.2M',
      videoCount: 450
    },
    ...overrides
  };
}

export function createMockYouTubeChannel(overrides: Partial<any> = {}) {
  return {
    id: 'UC123456789',
    title: 'YouTube Channel',
    description: 'A YouTube channel',
    thumbnailUrl: 'https://yt3.ggpht.com/test.jpg',
    subscriberCount: 100000,
    videoCount: 500,
    customUrl: '@testchannel',
    ...overrides
  };
}

export function createMockYouTubePlaylist(overrides: Partial<any> = {}) {
  return {
    id: 'PL123456789',
    title: 'YouTube Playlist',
    description: 'A YouTube playlist',
    thumbnailUrl: 'https://i.ytimg.com/vi/test/hqdefault.jpg',
    channelId: 'UC123456789',
    channelTitle: 'YouTube Channel',
    itemCount: 25,
    ...overrides
  };
}

/**
 * Create a list of mock videos
 */
export function createMockVideoList(count: number = 10) {
  return Array.from({ length: count }, (_, i) =>
    createMockVideo({
      id: `video_${i + 1}`,
      title: `Video ${i + 1}`,
      viewCount: (i + 1) * 1000
    })
  );
}

/**
 * Create a list of mock channels
 */
export function createMockChannelList(count: number = 5) {
  return Array.from({ length: count }, (_, i) =>
    createMockChannel({
      id: `channel_${i + 1}`,
      name: `Channel ${i + 1}`,
      subscriberCount: (i + 1) * 10000
    })
  );
}

/**
 * Create a list of mock categories
 */
export function createMockCategoryList(count: number = 5) {
  return Array.from({ length: count }, (_, i) =>
    createMockCategory({
      id: `category_${i + 1}`,
      name: `Category ${i + 1}`
    })
  );
}
