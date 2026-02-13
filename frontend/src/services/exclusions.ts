import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';
import type { Exclusion, CreateExclusionPayload, ExclusionPage } from '@/types/exclusions';

// Types

export interface ChannelExclusion {
  type: 'video' | 'playlist';
  youtubeId: string;
  title?: string;
  thumbnailUrl?: string;
  addedAt?: string;
}

export interface ChannelExclusions {
  videos: string[];
  playlists: string[];
}

export interface ExclusionResponse {
  success: boolean;
  message?: string;
}

// Channel Exclusions

export async function fetchChannelExclusions(channelId: string): Promise<ChannelExclusions> {
  const response = await authorizedJsonFetch<ChannelExclusions>(
    `/api/admin/channels/${channelId}/exclusions`
  );
  return response;
}

export async function addChannelExclusion(
  channelId: string,
  type: 'video' | 'playlist',
  youtubeId: string
): Promise<ChannelExclusions> {
  const response = await authorizedJsonFetch<ChannelExclusions>(
    `/api/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`,
    { method: 'POST' }
  );
  return response;
}

export async function removeChannelExclusion(
  channelId: string,
  type: 'video' | 'playlist',
  youtubeId: string
): Promise<ChannelExclusions> {
  const response = await authorizedJsonFetch<ChannelExclusions>(
    `/api/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`,
    { method: 'DELETE' }
  );
  return response;
}

// Playlist Exclusions

export async function fetchPlaylistExclusions(playlistId: string): Promise<string[]> {
  const response = await authorizedJsonFetch<string[]>(
    `/api/admin/registry/playlists/${playlistId}/exclusions`
  );
  return response;
}

export async function addPlaylistExclusion(
  playlistId: string,
  videoId: string
): Promise<string[]> {
  const response = await authorizedJsonFetch<string[]>(
    `/api/admin/registry/playlists/${playlistId}/exclusions/${videoId}`,
    { method: 'POST' }
  );
  return response;
}

export async function removePlaylistExclusion(
  playlistId: string,
  videoId: string
): Promise<string[]> {
  const response = await authorizedJsonFetch<string[]>(
    `/api/admin/registry/playlists/${playlistId}/exclusions/${videoId}`,
    { method: 'DELETE' }
  );
  return response;
}

// Workspace Exclusions

export interface FetchExclusionsParams {
  cursor?: string | null;
  limit?: number;
  /** Filter by parent type: CHANNEL or PLAYLIST */
  parentType?: string;
  /** Filter by exclude type: VIDEO or PLAYLIST */
  excludeType?: string;
  /** Search term for filtering */
  search?: string;
}

export async function fetchExclusionsPage(params: FetchExclusionsParams = {}): Promise<ExclusionPage> {
  const queryParams = new URLSearchParams();
  if (params.cursor) {
    queryParams.set('cursor', params.cursor);
  }
  if (params.limit) {
    queryParams.set('limit', params.limit.toString());
  }
  if (params.parentType) {
    queryParams.set('parentType', params.parentType);
  }
  if (params.excludeType) {
    queryParams.set('excludeType', params.excludeType);
  }
  if (params.search) {
    queryParams.set('search', params.search);
  }

  const queryString = queryParams.toString();
  const url = `/api/admin/exclusions${queryString ? '?' + queryString : ''}`;

  const response = await authorizedJsonFetch<ExclusionPage>(url);
  return response;
}

export async function createExclusion(payload: CreateExclusionPayload): Promise<Exclusion> {
  const response = await authorizedJsonFetch<Exclusion>(
    '/api/admin/exclusions',
    {
      method: 'POST',
      body: JSON.stringify(payload)
    }
  );
  return response;
}

export async function removeExclusion(exclusionId: string): Promise<void> {
  await authorizedJsonFetch<void>(
    `/api/admin/exclusions/${encodeURIComponent(exclusionId)}`,
    { method: 'DELETE' }
  );
}
