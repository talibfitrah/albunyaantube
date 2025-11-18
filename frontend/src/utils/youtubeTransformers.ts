/**
 * YouTube Data Transformers
 * Pure functions to transform API DTOs to UI models
 */

import type { EnrichedSearchResult } from '@/types/api';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';

/**
 * Parse ISO 8601 duration to seconds (e.g., "PT1H2M30S" -> 3750)
 */
export function parseDuration(duration: string): number {
  if (!duration) return 0;

  const match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
  if (!match) return 0;

  const hours = parseInt(match[1] || '0');
  const minutes = parseInt(match[2] || '0');
  const seconds = parseInt(match[3] || '0');

  return hours * 3600 + minutes * 60 + seconds;
}

/**
 * Transform EnrichedSearchResult to AdminSearchChannelResult
 */
export function transformChannelResult(channel: EnrichedSearchResult): AdminSearchChannelResult {
  return {
    id: channel.id || '',
    ytId: channel.id || '',
    name: channel.title || '',
    avatarUrl: channel.thumbnailUrl || '',
    subscriberCount: channel.subscriberCount || 0,
    publishedAt: channel.publishedAt,
    categories: [],
    includeState: 'NOT_INCLUDED',
    excludedItemCounts: { videos: 0, playlists: 0 },
    excludedPlaylistIds: [],
    excludedVideoIds: [],
    bulkEligible: true
  };
}

/**
 * Transform array of EnrichedSearchResult to AdminSearchChannelResult[]
 */
export function transformChannelResults(channels: EnrichedSearchResult[]): AdminSearchChannelResult[] {
  return channels.map(transformChannelResult);
}

/**
 * Transform array of EnrichedSearchResult to AdminSearchPlaylistResult[]
 */
export function transformPlaylistResults(playlists: EnrichedSearchResult[]): AdminSearchPlaylistResult[] {
  return playlists.map(playlist => ({
    id: playlist.id || '',
    ytId: playlist.id || '',
    title: playlist.title || '',
    thumbnailUrl: playlist.thumbnailUrl || '',
    videoThumbnails: playlist.videoThumbnails || [],
    itemCount: playlist.itemCount || 0,
    publishedAt: playlist.publishedAt,
    owner: {
      id: playlist.channelId || '',
      ytId: playlist.channelId || '',
      name: playlist.channelTitle || '',
      avatarUrl: '',
      subscriberCount: 0,
      categories: []
    },
    categories: [],
    downloadable: true,
    includeState: 'NOT_INCLUDED',
    parentChannelId: playlist.channelId || '',
    excludedVideoCount: 0,
    excludedVideoIds: [],
    bulkEligible: true
  }));
}

/**
 * Transform array of EnrichedSearchResult to AdminSearchVideoResult[]
 */
export function transformVideoResults(videos: EnrichedSearchResult[]): AdminSearchVideoResult[] {
  return videos.map(video => ({
    id: video.id || '',
    ytId: video.id || '',
    title: video.title || '',
    thumbnailUrl: video.thumbnailUrl || '',
    durationSeconds: video.duration ? parseDuration(video.duration) : 0,
    publishedAt: video.publishedAt || '',
    viewCount: video.viewCount || 0,
    channel: {
      id: video.channelId || '',
      ytId: video.channelId || '',
      name: video.channelTitle || '',
      avatarUrl: '',
      subscriberCount: 0,
      categories: []
    },
    categories: [],
    bookmarked: null,
    downloaded: null,
    includeState: 'NOT_INCLUDED',
    parentChannelId: video.channelId || '',
    parentPlaylistIds: []
  }));
}
