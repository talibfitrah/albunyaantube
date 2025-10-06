/**
 * YouTube Service
 * Real backend API integration for YouTube search and content preview
 */

import apiClient from './api/client';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult, YouTubeEnrichedSearchResult } from '@/types/registry';

interface SearchPageResponse {
  items: YouTubeEnrichedSearchResult[];
  nextPageToken?: string;
  totalResults?: number;
}

interface YouTubeSearchResponse {
  channels: AdminSearchChannelResult[];
  playlists: AdminSearchPlaylistResult[];
  videos: AdminSearchVideoResult[];
  nextPageToken?: string;
  totalResults?: number;
}

/**
 * Search YouTube for channels, playlists, or videos with pagination support
 */
export async function searchYouTube(
  query: string,
  type: 'all' | 'channels' | 'playlists' | 'videos' = 'all',
  pageToken?: string
): Promise<YouTubeSearchResponse> {
  // Use unified search for 'all' - MUCH faster (single API call) with pagination
  if (type === 'all') {
    const response = await apiClient.get<SearchPageResponse>('/api/admin/youtube/search/all', {
      params: { query, pageToken }
    });

    // Separate by type but preserve mixed order
    const channels: any[] = [];
    const playlists: any[] = [];
    const videos: any[] = [];

    response.data.items.forEach(item => {
      if (item.type === 'channel') channels.push(item);
      else if (item.type === 'playlist') playlists.push(item);
      else if (item.type === 'video') videos.push(item);
    });

    return {
      channels: transformChannelResults(channels),
      playlists: transformPlaylistResults(playlists),
      videos: transformVideoResults(videos),
      nextPageToken: response.data.nextPageToken,
      totalResults: response.data.totalResults
    };
  }

  // Handle single-type responses
  const endpoint = `/api/admin/youtube/search/${type}`;
  const response = await apiClient.get(endpoint, {
    params: { query }
  });

  const results = response.data;
  return {
    channels: type === 'channels' ? transformChannelResults(results) : [],
    playlists: type === 'playlists' ? transformPlaylistResults(results) : [],
    videos: type === 'videos' ? transformVideoResults(results) : []
  };
}

/**
 * Get channel details with videos and playlists
 */
export async function getChannelDetails(channelId: string) {
  const [channelResponse, videosResponse, playlistsResponse] = await Promise.all([
    apiClient.get(`/api/admin/youtube/channels/${channelId}`),
    apiClient.get(`/api/admin/youtube/channels/${channelId}/videos`),
    apiClient.get(`/api/admin/youtube/channels/${channelId}/playlists`)
  ]);

  return {
    channel: transformChannelResult(channelResponse.data),
    videos: transformVideoResults(videosResponse.data),
    playlists: transformPlaylistResults(playlistsResponse.data)
  };
}

/**
 * Add a channel, playlist, or video to pending approval queue
 */
export async function addToPendingApprovals(
  item: AdminSearchChannelResult | AdminSearchPlaylistResult | AdminSearchVideoResult,
  itemType: 'channel' | 'playlist' | 'video',
  categoryIds: string[] = []
): Promise<void> {
  if (itemType === 'channel') {
    const channel = item as AdminSearchChannelResult;
    const payload = {
      youtubeId: channel.ytId || channel.id,
      name: channel.name,
      description: '',
      thumbnailUrl: channel.avatarUrl,
      subscribers: channel.subscriberCount,
      videoCount: 0,
      categoryIds,
      status: 'PENDING'
    };
    await apiClient.post('/api/admin/registry/channels', payload);
  } else if (itemType === 'playlist') {
    const playlist = item as AdminSearchPlaylistResult;
    const payload = {
      youtubeId: playlist.ytId || playlist.id,
      title: playlist.title,
      description: '',
      thumbnailUrl: playlist.thumbnailUrl,
      itemCount: playlist.itemCount,
      categoryIds,
      status: 'PENDING'
    };
    await apiClient.post('/api/admin/registry/playlists', payload);
  } else if (itemType === 'video') {
    const video = item as AdminSearchVideoResult;
    const payload = {
      youtubeId: video.ytId || video.id,
      title: video.title,
      description: '',
      thumbnailUrl: video.thumbnailUrl,
      durationSeconds: video.durationSeconds,
      viewCount: video.viewCount,
      categoryIds,
      status: 'PENDING'
    };
    await apiClient.post('/api/admin/registry/videos', payload);
  }
}

/**
 * @deprecated Use addToPendingApprovals instead
 * Toggle include/exclude state for a channel or playlist
 */
export async function toggleIncludeState(
  itemId: string,
  itemType: 'channel' | 'playlist' | 'video',
  newState: 'INCLUDED' | 'NOT_INCLUDED'
): Promise<void> {
  const endpoint = itemType === 'channel'
    ? `/api/admin/registry/channels/${itemId}/toggle`
    : `/api/admin/registry/playlists/${itemId}/toggle`;

  await apiClient.patch(endpoint);
}

// Transform EnrichedSearchResult to AdminSearchChannelResult
function transformChannelResult(channel: YouTubeEnrichedSearchResult | any): AdminSearchChannelResult {
  // Handle both EnrichedSearchResult and legacy Google API format
  const channelId = channel.id || (typeof channel.id === 'object' ? channel.id.channelId : channel.id);

  return {
    id: channelId,
    ytId: channelId,
    name: channel.title || channel.snippet?.title || '',
    avatarUrl: channel.thumbnailUrl || channel.snippet?.thumbnails?.medium?.url || channel.snippet?.thumbnails?.default?.url || '',
    subscriberCount: channel.subscriberCount || parseInt(channel.statistics?.subscriberCount || '0'),
    publishedAt: channel.publishedAt || channel.snippet?.publishedAt,
    categories: [],
    includeState: 'NOT_INCLUDED',
    excludedItemCounts: { videos: 0, playlists: 0 },
    excludedPlaylistIds: [],
    excludedVideoIds: [],
    bulkEligible: true
  };
}

function transformChannelResults(channels: any[]): AdminSearchChannelResult[] {
  return channels.map(transformChannelResult);
}

// Transform EnrichedSearchResult to AdminSearchPlaylistResult
function transformPlaylistResults(playlists: any[]): AdminSearchPlaylistResult[] {
  return playlists.map(playlist => {
    // Handle both EnrichedSearchResult and legacy Google API format
    const playlistId = playlist.id || (typeof playlist.id === 'object' ? playlist.id.playlistId : playlist.id);
    const channelId = playlist.channelId || playlist.snippet?.channelId || '';
    const channelTitle = playlist.channelTitle || playlist.snippet?.channelTitle || '';

    return {
      id: playlistId,
      ytId: playlistId,
      title: playlist.title || playlist.snippet?.title || '',
      thumbnailUrl: playlist.thumbnailUrl || playlist.snippet?.thumbnails?.medium?.url || playlist.snippet?.thumbnails?.default?.url || '',
      videoThumbnails: playlist.videoThumbnails || [],
      itemCount: playlist.itemCount || parseInt(playlist.contentDetails?.itemCount || '0'),
      publishedAt: playlist.publishedAt || playlist.snippet?.publishedAt,
      owner: {
        id: channelId,
        ytId: channelId,
        name: channelTitle,
        avatarUrl: '',
        subscriberCount: 0,
        categories: []
      },
      categories: [],
      downloadable: true,
      includeState: 'NOT_INCLUDED',
      parentChannelId: channelId,
      excludedVideoCount: 0,
      excludedVideoIds: [],
      bulkEligible: true
    };
  });
}

// Transform EnrichedSearchResult to AdminSearchVideoResult
function transformVideoResults(videos: any[]): AdminSearchVideoResult[] {
  return videos.map(video => {
    // Handle both EnrichedSearchResult and legacy Google API format
    const videoId = video.id || (typeof video.id === 'object' ? video.id.videoId : video.id);
    const channelId = video.channelId || video.snippet?.channelId || '';
    const channelTitle = video.channelTitle || video.snippet?.channelTitle || '';

    return {
      id: videoId,
      ytId: videoId,
      title: video.title || video.snippet?.title || '',
      thumbnailUrl: video.thumbnailUrl || video.snippet?.thumbnails?.medium?.url || video.snippet?.thumbnails?.default?.url || '',
      durationSeconds: video.duration ? parseDuration(video.duration) : parseDuration(video.contentDetails?.duration || ''),
      publishedAt: video.publishedAt || video.snippet?.publishedAt || '',
      viewCount: video.viewCount || parseInt(video.statistics?.viewCount || '0'),
      channel: {
        id: channelId,
        ytId: channelId,
        name: channelTitle,
        avatarUrl: '',
        subscriberCount: 0,
        categories: []
      },
      categories: [],
      bookmarked: null,
      downloaded: null,
      includeState: 'NOT_INCLUDED',
      parentChannelId: channelId,
      parentPlaylistIds: []
    };
  });
}

// Parse ISO 8601 duration to seconds (e.g., "PT1H2M30S" -> 3750)
function parseDuration(duration: string): number {
  if (!duration) return 0;

  const match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
  if (!match) return 0;

  const hours = parseInt(match[1] || '0');
  const minutes = parseInt(match[2] || '0');
  const seconds = parseInt(match[3] || '0');

  return hours * 3600 + minutes * 60 + seconds;
}
