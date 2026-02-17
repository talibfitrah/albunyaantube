/**
 * YouTube Data API v3 - Direct Client
 *
 * Calls YouTube Data API directly from the browser for search operations.
 * This removes the backend as a proxy for YouTube search.
 *
 * Requires VITE_YOUTUBE_API_KEY environment variable.
 * The API key should be a browser-restricted key from Google Cloud Console.
 */

import type { EnrichedSearchResult, SearchPageResponse } from '@/types/api';

const YOUTUBE_API_BASE = 'https://www.googleapis.com/youtube/v3';

function getApiKey(): string | null {
  return import.meta.env.VITE_YOUTUBE_API_KEY || null;
}

/**
 * Check if YouTube Data API is available (API key configured)
 */
export function isYouTubeDataApiAvailable(): boolean {
  return !!getApiKey();
}

interface YouTubeSearchItem {
  id: { kind: string; videoId?: string; channelId?: string; playlistId?: string };
  snippet: {
    title: string;
    description: string;
    publishedAt: string;
    channelId: string;
    channelTitle: string;
    thumbnails: {
      default?: { url: string };
      medium?: { url: string };
      high?: { url: string };
    };
  };
}

interface YouTubeSearchResponse {
  items: YouTubeSearchItem[];
  nextPageToken?: string;
  pageInfo?: { totalResults: number; resultsPerPage: number };
}

interface YouTubeChannelItem {
  id: string;
  snippet: { title: string; description: string; thumbnails: { high?: { url: string }; medium?: { url: string } } };
  statistics: { subscriberCount: string; videoCount: string; viewCount: string };
}

interface YouTubeVideoItem {
  id: string;
  snippet: { title: string; description: string; channelId: string; channelTitle: string; publishedAt: string; thumbnails: { high?: { url: string }; medium?: { url: string } } };
  contentDetails: { duration: string };
  statistics: { viewCount: string; likeCount?: string };
}

interface YouTubePlaylistItem {
  id: string;
  snippet: { title: string; description: string; channelId: string; channelTitle: string; thumbnails: { high?: { url: string }; medium?: { url: string } } };
  contentDetails: { itemCount: number };
}

async function youtubeGet<T>(endpoint: string, params: Record<string, string>): Promise<T> {
  const apiKey = getApiKey();
  if (!apiKey) throw new Error('YouTube Data API key not configured');

  const url = new URL(`${YOUTUBE_API_BASE}/${endpoint}`);
  url.searchParams.set('key', apiKey);
  for (const [key, value] of Object.entries(params)) {
    if (value) url.searchParams.set(key, value);
  }

  const response = await fetch(url.toString());
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`YouTube API error ${response.status}: ${body}`);
  }
  return response.json();
}

/**
 * Search YouTube for all content types with pagination.
 * Returns data in SearchPageResponse format compatible with the backend proxy.
 */
export async function searchAll(query: string, pageToken?: string): Promise<SearchPageResponse> {
  const searchData = await youtubeGet<YouTubeSearchResponse>('search', {
    part: 'snippet',
    q: query,
    type: 'video,channel,playlist',
    maxResults: '20',
    pageToken: pageToken || ''
  });

  // Collect IDs for enrichment
  const channelIds: string[] = [];
  const videoIds: string[] = [];
  const playlistIds: string[] = [];

  for (const item of searchData.items) {
    if (item.id.channelId) channelIds.push(item.id.channelId);
    else if (item.id.videoId) videoIds.push(item.id.videoId);
    else if (item.id.playlistId) playlistIds.push(item.id.playlistId);
  }

  // Fetch enrichment data in parallel
  const [channelStats, videoStats, playlistStats] = await Promise.all([
    channelIds.length > 0 ? getChannelStats(channelIds) : Promise.resolve(new Map<string, YouTubeChannelItem>()),
    videoIds.length > 0 ? getVideoStats(videoIds) : Promise.resolve(new Map<string, YouTubeVideoItem>()),
    playlistIds.length > 0 ? getPlaylistStats(playlistIds) : Promise.resolve(new Map<string, YouTubePlaylistItem>())
  ]);

  // Map to EnrichedSearchResult
  const items: EnrichedSearchResult[] = searchData.items.map(item => {
    const thumbnail = item.snippet.thumbnails.high?.url || item.snippet.thumbnails.medium?.url || item.snippet.thumbnails.default?.url || '';

    if (item.id.channelId) {
      const stats = channelStats.get(item.id.channelId);
      return {
        id: item.id.channelId,
        type: 'channel' as const,
        title: item.snippet.title,
        description: item.snippet.description,
        thumbnailUrl: stats?.snippet.thumbnails.high?.url || thumbnail,
        subscriberCount: stats ? parseInt(stats.statistics.subscriberCount || '0') : 0,
        videoCount: stats ? parseInt(stats.statistics.videoCount || '0') : 0,
        publishedAt: item.snippet.publishedAt
      };
    } else if (item.id.videoId) {
      const stats = videoStats.get(item.id.videoId);
      return {
        id: item.id.videoId,
        type: 'video' as const,
        title: item.snippet.title,
        description: item.snippet.description,
        thumbnailUrl: thumbnail,
        channelId: item.snippet.channelId,
        channelTitle: item.snippet.channelTitle,
        viewCount: stats ? parseInt(stats.statistics.viewCount || '0') : 0,
        duration: stats?.contentDetails.duration || 'PT0S',
        publishedAt: item.snippet.publishedAt
      };
    } else {
      const stats = playlistStats.get(item.id.playlistId!);
      return {
        id: item.id.playlistId!,
        type: 'playlist' as const,
        title: item.snippet.title,
        description: item.snippet.description,
        thumbnailUrl: thumbnail,
        channelId: item.snippet.channelId,
        channelTitle: item.snippet.channelTitle,
        itemCount: stats?.contentDetails.itemCount || 0,
        publishedAt: item.snippet.publishedAt
      };
    }
  });

  return {
    items,
    nextPageToken: searchData.nextPageToken || null,
    totalResults: searchData.pageInfo?.totalResults || items.length
  };
}

/**
 * Search YouTube for a specific content type.
 */
export async function searchByType(
  query: string,
  type: 'channel' | 'playlist' | 'video'
): Promise<EnrichedSearchResult[]> {
  const typeMap: Record<string, string> = { channel: 'channel', playlist: 'playlist', video: 'video' };

  const searchData = await youtubeGet<YouTubeSearchResponse>('search', {
    part: 'snippet',
    q: query,
    type: typeMap[type],
    maxResults: '20'
  });

  const ids: string[] = searchData.items.map(item =>
    item.id.channelId || item.id.videoId || item.id.playlistId || ''
  ).filter(Boolean);

  // Enrich based on type
  if (type === 'channel' && ids.length) {
    const stats = await getChannelStats(ids);
    return searchData.items.map(item => {
      const id = item.id.channelId || '';
      const s = stats.get(id);
      return {
        id,
        type: 'channel' as const,
        title: item.snippet.title,
        description: item.snippet.description,
        thumbnailUrl: s?.snippet.thumbnails.high?.url || item.snippet.thumbnails.high?.url || '',
        subscriberCount: s ? parseInt(s.statistics.subscriberCount || '0') : 0,
        videoCount: s ? parseInt(s.statistics.videoCount || '0') : 0,
        publishedAt: item.snippet.publishedAt
      };
    });
  }

  if (type === 'video' && ids.length) {
    const stats = await getVideoStats(ids);
    return searchData.items.map(item => {
      const id = item.id.videoId || '';
      const s = stats.get(id);
      return {
        id,
        type: 'video' as const,
        title: item.snippet.title,
        description: item.snippet.description,
        thumbnailUrl: item.snippet.thumbnails.high?.url || '',
        channelId: item.snippet.channelId,
        channelTitle: item.snippet.channelTitle,
        viewCount: s ? parseInt(s.statistics.viewCount || '0') : 0,
        duration: s?.contentDetails.duration || 'PT0S',
        publishedAt: item.snippet.publishedAt
      };
    });
  }

  if (type === 'playlist' && ids.length) {
    const stats = await getPlaylistStats(ids);
    return searchData.items.map(item => {
      const id = item.id.playlistId || '';
      const s = stats.get(id);
      return {
        id,
        type: 'playlist' as const,
        title: item.snippet.title,
        description: item.snippet.description,
        thumbnailUrl: item.snippet.thumbnails.high?.url || '',
        channelId: item.snippet.channelId,
        channelTitle: item.snippet.channelTitle,
        itemCount: s?.contentDetails.itemCount || 0,
        publishedAt: item.snippet.publishedAt
      };
    });
  }

  return [];
}

/**
 * Fetch channel statistics for enrichment.
 */
async function getChannelStats(ids: string[]): Promise<Map<string, YouTubeChannelItem>> {
  const data = await youtubeGet<{ items: YouTubeChannelItem[] }>('channels', {
    part: 'snippet,statistics',
    id: ids.join(',')
  });
  const map = new Map<string, YouTubeChannelItem>();
  for (const item of data.items) {
    map.set(item.id, item);
  }
  return map;
}

/**
 * Fetch video statistics and content details for enrichment.
 */
async function getVideoStats(ids: string[]): Promise<Map<string, YouTubeVideoItem>> {
  const data = await youtubeGet<{ items: YouTubeVideoItem[] }>('videos', {
    part: 'snippet,contentDetails,statistics',
    id: ids.join(',')
  });
  const map = new Map<string, YouTubeVideoItem>();
  for (const item of data.items) {
    map.set(item.id, item);
  }
  return map;
}

/**
 * Fetch playlist statistics for enrichment.
 */
async function getPlaylistStats(ids: string[]): Promise<Map<string, YouTubePlaylistItem>> {
  const data = await youtubeGet<{ items: YouTubePlaylistItem[] }>('playlists', {
    part: 'snippet,contentDetails',
    id: ids.join(',')
  });
  const map = new Map<string, YouTubePlaylistItem>();
  for (const item of data.items) {
    map.set(item.id, item);
  }
  return map;
}
