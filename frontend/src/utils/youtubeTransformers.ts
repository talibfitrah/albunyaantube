/**
 * YouTube Data Transformers
 * Pure functions to transform API DTOs to UI models
 */

import type {
  EnrichedSearchResult,
  StreamItemDto,
  PlaylistItemDto,
  PlaylistDetailsDto,
  ChannelDetailsDto,
  Channel,
  Playlist,
  Video
} from '@/types/api';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';

/**
 * Parse ISO 8601 duration to seconds (e.g., "PT1H2M30S" -> 3750, "P3DT4H" -> 273600)
 * Supports full ISO 8601 period format including days and fractional seconds
 */
export function parseDuration(duration: string): number {
  if (!duration) return 0;

  // Match ISO 8601 duration: P[nD][T[nH][nM][n.nS]]
  const match = duration.match(/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?$/);
  if (!match) return 0;

  const days = parseInt(match[1] || '0');
  const hours = parseInt(match[2] || '0');
  const minutes = parseInt(match[3] || '0');
  const seconds = parseFloat(match[4] || '0');

  return days * 86400 + hours * 3600 + minutes * 60 + seconds;
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

// ============================================================================
// DTO to EnrichedSearchResult Converters
// Used to normalize various DTO types into the common search result format
// ============================================================================

/**
 * Convert ChannelDetailsDto to EnrichedSearchResult
 */
export function channelDtoToSearchResult(dto: ChannelDetailsDto): EnrichedSearchResult {
  return {
    id: dto.id || '',
    title: dto.name || '',
    thumbnailUrl: dto.thumbnailUrl || '',
    description: dto.description || '',
    subscriberCount: dto.subscriberCount || 0,
    videoCount: dto.streamCount || 0,
    type: 'channel'
  };
}

/**
 * Convert StreamItemDto array to EnrichedSearchResult array (for videos)
 */
export function streamItemsToSearchResults(
  items: StreamItemDto[],
  channelId: string
): EnrichedSearchResult[] {
  return items.map(video => ({
    id: video.id || '',
    title: video.name || '',
    thumbnailUrl: video.thumbnailUrl || '',
    description: '',
    channelId,
    channelTitle: video.uploaderName || '',
    viewCount: video.viewCount || 0,
    duration: video.duration ? `PT${video.duration}S` : 'PT0S',
    publishedAt: video.uploadDate || '',
    type: 'video'
  }));
}

/**
 * Convert PlaylistItemDto array to EnrichedSearchResult array
 */
export function playlistItemsToSearchResults(
  items: PlaylistItemDto[],
  channelId: string
): EnrichedSearchResult[] {
  return items.map(playlist => ({
    id: playlist.id || '',
    title: playlist.name || '',
    thumbnailUrl: playlist.thumbnailUrl || '',
    description: '',
    channelId,
    channelTitle: playlist.uploaderName || '',
    itemCount: playlist.streamCount || 0,
    type: 'playlist'
  }));
}

// ============================================================================
// Simple Item Mappers
// Convert DTOs to simplified UI models for lists
// ============================================================================

export interface SimpleVideoItem {
  id: string;
  title: string;
  thumbnailUrl: string;
  publishedAt: string;
}

export interface SimplePlaylistItem {
  id: string;
  title: string;
  thumbnailUrl: string;
  itemCount: number;
}

export interface PlaylistVideoItem {
  id: string;
  videoId: string;
  title: string;
  thumbnailUrl: string;
}

export interface PlaylistInfo {
  title: string;
  thumbnailUrl: string;
  itemCount: number;
}

/**
 * Map StreamItemDto array to simple video items
 */
export function mapStreamItemsToVideos(items: StreamItemDto[]): SimpleVideoItem[] {
  return items.map(item => ({
    id: item.id || '',
    title: item.name || '',
    thumbnailUrl: item.thumbnailUrl || '',
    publishedAt: item.uploadDate || ''
  }));
}

/**
 * Map PlaylistItemDto array to simple playlist items
 */
export function mapPlaylistItemsToPlaylists(items: PlaylistItemDto[]): SimplePlaylistItem[] {
  return items.map(item => ({
    id: item.id || '',
    title: item.name || '',
    thumbnailUrl: item.thumbnailUrl || '',
    itemCount: item.streamCount || 0
  }));
}

/**
 * Map StreamItemDto array to playlist video items
 */
export function mapStreamItemsToPlaylistVideos(items: StreamItemDto[]): PlaylistVideoItem[] {
  return items.map(item => ({
    id: item.id || '',
    videoId: item.id || '',
    title: item.name || '',
    thumbnailUrl: item.thumbnailUrl || ''
  }));
}

/**
 * Map PlaylistDetailsDto to simple playlist info
 */
export function mapPlaylistDetailsToInfo(dto: PlaylistDetailsDto): PlaylistInfo {
  return {
    title: dto.name || '',
    thumbnailUrl: dto.thumbnailUrl || '',
    itemCount: dto.streamCount || 0
  };
}

// ============================================================================
// Payload Builders
// Create API payloads from UI search results
// ============================================================================

/**
 * Build channel payload for pending approval
 */
export function buildChannelPayload(
  channel: AdminSearchChannelResult,
  categoryIds: string[]
): Omit<Channel, 'id'> {
  return {
    youtubeId: channel.ytId,
    name: channel.name || '',
    description: null,
    thumbnailUrl: channel.avatarUrl || null,
    subscribers: channel.subscriberCount || null,
    videoCount: null,
    categoryIds,
    status: 'PENDING'
  };
}

/**
 * Build playlist payload for pending approval
 */
export function buildPlaylistPayload(
  playlist: AdminSearchPlaylistResult,
  categoryIds: string[]
): Omit<Playlist, 'id'> {
  return {
    youtubeId: playlist.ytId,
    title: playlist.title || '',
    description: null,
    thumbnailUrl: playlist.thumbnailUrl || null,
    itemCount: playlist.itemCount || null,
    categoryIds,
    status: 'PENDING',
    channelId: null
  };
}

/**
 * Build video payload for pending approval
 */
export function buildVideoPayload(
  video: AdminSearchVideoResult,
  categoryIds: string[]
): Omit<Video, 'id'> {
  return {
    youtubeId: video.ytId,
    title: video.title || '',
    description: null,
    thumbnailUrl: video.thumbnailUrl || null,
    durationSeconds: video.durationSeconds || null,
    viewCount: video.viewCount || null,
    categoryIds,
    status: 'PENDING',
    channelId: null
  };
}
