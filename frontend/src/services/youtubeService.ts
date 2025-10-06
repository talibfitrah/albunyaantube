/**
 * YouTube Service
 * Real backend API integration for YouTube search and content preview
 */

import apiClient from './api/client';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';

interface YouTubeSearchResponse {
  channels: AdminSearchChannelResult[];
  playlists: AdminSearchPlaylistResult[];
  videos: AdminSearchVideoResult[];
}

/**
 * Search YouTube for channels, playlists, or videos
 */
export async function searchYouTube(
  query: string,
  type: 'all' | 'channels' | 'playlists' | 'videos' = 'all'
): Promise<YouTubeSearchResponse> {
  const endpoint = `/api/admin/youtube/search/${type}`;
  const response = await apiClient.get(endpoint, {
    params: { query }
  });

  // Handle 'all' response with all three types
  if (type === 'all') {
    return {
      channels: transformChannelResults(response.data.channels || []),
      playlists: transformPlaylistResults(response.data.playlists || []),
      videos: transformVideoResults(response.data.videos || [])
    };
  }

  // Handle single-type responses
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
 * Add a channel or playlist to pending approval queue
 */
export async function addToPendingApprovals(
  item: AdminSearchChannelResult | AdminSearchPlaylistResult,
  itemType: 'channel' | 'playlist'
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
      categoryIds: [],
      status: 'PENDING'
    };
    await apiClient.post('/api/admin/registry/channels', payload);
  } else {
    const playlist = item as AdminSearchPlaylistResult;
    const payload = {
      youtubeId: playlist.ytId || playlist.id,
      title: playlist.title,
      description: '',
      thumbnailUrl: playlist.thumbnailUrl,
      itemCount: playlist.itemCount,
      categoryIds: [],
      status: 'PENDING'
    };
    await apiClient.post('/api/admin/registry/playlists', payload);
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

// Transform Google API Channel to AdminSearchChannelResult
function transformChannelResult(channel: any): AdminSearchChannelResult {
  // Extract channelId from nested object if present
  const channelId = typeof channel.id === 'object' ? channel.id.channelId : channel.id;

  return {
    id: channelId,
    ytId: channelId,
    name: channel.snippet?.title || '',
    avatarUrl: channel.snippet?.thumbnails?.medium?.url || channel.snippet?.thumbnails?.default?.url || '',
    subscriberCount: parseInt(channel.statistics?.subscriberCount || '0'),
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

// Transform Google API SearchResult to AdminSearchPlaylistResult
function transformPlaylistResults(playlists: any[]): AdminSearchPlaylistResult[] {
  return playlists.map(playlist => {
    // Extract playlistId from nested object if present
    const playlistId = typeof playlist.id === 'object' ? playlist.id.playlistId : playlist.id;

    return {
      id: playlistId,
      ytId: playlistId,
      title: playlist.snippet?.title || '',
      thumbnailUrl: playlist.snippet?.thumbnails?.medium?.url || playlist.snippet?.thumbnails?.default?.url || '',
      itemCount: parseInt(playlist.contentDetails?.itemCount || '0'),
      owner: {
        id: playlist.snippet?.channelId || '',
        ytId: playlist.snippet?.channelId || '',
        name: playlist.snippet?.channelTitle || '',
        avatarUrl: '',
        subscriberCount: 0,
        categories: []
      },
      categories: [],
      downloadable: true,
      includeState: 'NOT_INCLUDED',
      parentChannelId: playlist.snippet?.channelId || '',
      excludedVideoCount: 0,
      excludedVideoIds: [],
      bulkEligible: true
    };
  });
}

// Transform Google API SearchResult to AdminSearchVideoResult
function transformVideoResults(videos: any[]): AdminSearchVideoResult[] {
  return videos.map(video => {
    const videoId = typeof video.id === 'object' ? video.id.videoId : video.id;

    return {
      id: videoId,
      ytId: videoId,
      title: video.snippet?.title || '',
      thumbnailUrl: video.snippet?.thumbnails?.medium?.url || video.snippet?.thumbnails?.default?.url || '',
      durationSeconds: parseDuration(video.contentDetails?.duration || ''),
      publishedAt: video.snippet?.publishedAt || '',
      viewCount: parseInt(video.statistics?.viewCount || '0'),
      channel: {
        id: video.snippet?.channelId || '',
        ytId: video.snippet?.channelId || '',
        name: video.snippet?.channelTitle || '',
        avatarUrl: '',
        subscriberCount: 0,
        categories: []
      },
      categories: [],
      bookmarked: null,
      downloaded: null,
      includeState: 'NOT_INCLUDED',
      parentChannelId: video.snippet?.channelId || '',
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
