/**
 * YouTube Service
 * Real backend API integration for YouTube search and content preview
 *
 * Backend uses NewPipeExtractor for YouTube content extraction (no API key required).
 * All endpoints return the same data structures, but the backend implementation
 * has been migrated from YouTube Data API v3 to NewPipeExtractor.
 */

import apiClient from './api/client';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';
import type {
  EnrichedSearchResult,
  SearchPageResponse,
  StreamItemDto,
  PlaylistItemDto,
  PlaylistDetailsDto,
  ChannelDetailsDto,
  Channel,
  Playlist,
  Video
} from '@/types/api';
import {
  transformChannelResult,
  transformChannelResults,
  transformPlaylistResults,
  transformVideoResults,
  channelDtoToSearchResult,
  streamItemsToSearchResults,
  playlistItemsToSearchResults,
  mapStreamItemsToVideos,
  mapPlaylistItemsToPlaylists,
  mapStreamItemsToPlaylistVideos,
  mapPlaylistDetailsToInfo,
  buildChannelPayload,
  buildPlaylistPayload,
  buildVideoPayload
} from '@/utils/youtubeTransformers';

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
  if (type === 'all') {
    const response = await apiClient.get<SearchPageResponse>('/api/admin/youtube/search/all', {
      params: { query, pageToken }
    });

    const channels: EnrichedSearchResult[] = [];
    const playlists: EnrichedSearchResult[] = [];
    const videos: EnrichedSearchResult[] = [];

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

  const response = await apiClient.get<EnrichedSearchResult[]>(`/api/admin/youtube/search/${type}`, {
    params: { query }
  });

  return {
    channels: type === 'channels' ? transformChannelResults(response.data) : [],
    playlists: type === 'playlists' ? transformPlaylistResults(response.data) : [],
    videos: type === 'videos' ? transformVideoResults(response.data) : []
  };
}

/**
 * Get channel details with videos and playlists
 */
export async function getChannelDetails(channelId: string) {
  const [channelResponse, videosResponse, playlistsResponse] = await Promise.all([
    apiClient.get<ChannelDetailsDto>(`/api/admin/youtube/channels/${channelId}`),
    apiClient.get<StreamItemDto[]>(`/api/admin/youtube/channels/${channelId}/videos`),
    apiClient.get<PlaylistItemDto[]>(`/api/admin/youtube/channels/${channelId}/playlists`)
  ]);

  const channelSearchResult = channelDtoToSearchResult(channelResponse.data);
  const videosSearchResults = streamItemsToSearchResults(videosResponse.data, channelResponse.data.id || '');
  const playlistsSearchResults = playlistItemsToSearchResults(playlistsResponse.data, channelResponse.data.id || '');

  return {
    channel: transformChannelResult(channelSearchResult),
    videos: transformVideoResults(videosSearchResults),
    playlists: transformPlaylistResults(playlistsSearchResults)
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
    const payload = buildChannelPayload(item as AdminSearchChannelResult, categoryIds);
    await apiClient.post<Channel>('/api/admin/registry/channels', payload);
  } else if (itemType === 'playlist') {
    const payload = buildPlaylistPayload(item as AdminSearchPlaylistResult, categoryIds);
    await apiClient.post<Playlist>('/api/admin/registry/playlists', payload);
  } else if (itemType === 'video') {
    const payload = buildVideoPayload(item as AdminSearchVideoResult, categoryIds);
    await apiClient.post<Video>('/api/admin/registry/videos', payload);
  }
}

/**
 * Get videos from a YouTube channel
 */
export async function getChannelVideos(channelId: string, pageToken?: string, searchQuery?: string) {
  if (pageToken) {
    console.warn('[getChannelVideos] Backend pagination not supported yet. Token ignored.');
  }

  const params: Record<string, string> = {};
  if (searchQuery) params.q = searchQuery;

  const response = await apiClient.get<StreamItemDto[]>(
    `/api/admin/youtube/channels/${channelId}/videos`,
    { params }
  );

  return {
    items: mapStreamItemsToVideos(response.data),
    nextPageToken: undefined
  };
}

/**
 * Get playlists from a YouTube channel
 */
export async function getChannelPlaylists(channelId: string, pageToken?: string) {
  if (pageToken) {
    console.warn('[getChannelPlaylists] Backend pagination not supported yet. Token ignored.');
  }

  const response = await apiClient.get<PlaylistItemDto[]>(
    `/api/admin/youtube/channels/${channelId}/playlists`
  );

  return {
    items: mapPlaylistItemsToPlaylists(response.data),
    nextPageToken: undefined
  };
}

/**
 * Get playlist details from YouTube
 */
export async function getPlaylistDetails(playlistId: string) {
  const response = await apiClient.get<PlaylistDetailsDto>(
    `/api/admin/youtube/playlists/${playlistId}`
  );
  return mapPlaylistDetailsToInfo(response.data);
}

/**
 * Get videos in a YouTube playlist
 */
export async function getPlaylistVideos(playlistId: string, pageToken?: string, searchQuery?: string) {
  if (pageToken) {
    console.warn('[getPlaylistVideos] Backend pagination not supported yet. Token ignored.');
  }

  const params: Record<string, string> = {};
  if (searchQuery) params.q = searchQuery;

  const response = await apiClient.get<StreamItemDto[]>(
    `/api/admin/youtube/playlists/${playlistId}/videos`,
    { params }
  );

  return {
    items: mapStreamItemsToPlaylistVideos(response.data),
    nextPageToken: undefined
  };
}
