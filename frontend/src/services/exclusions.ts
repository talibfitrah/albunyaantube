import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

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
    `/admin/channels/${channelId}/exclusions`
  );
  return response;
}

export async function addChannelExclusion(
  channelId: string,
  type: 'video' | 'playlist',
  youtubeId: string
): Promise<ChannelExclusions> {
  const response = await authorizedJsonFetch<ChannelExclusions>(
    `/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`,
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
    `/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`,
    { method: 'DELETE' }
  );
  return response;
}

// Playlist Exclusions

export async function fetchPlaylistExclusions(playlistId: string): Promise<string[]> {
  const response = await authorizedJsonFetch<string[]>(
    `/admin/playlists/${playlistId}/exclusions`
  );
  return response;
}

export async function addPlaylistExclusion(
  playlistId: string,
  videoId: string
): Promise<string[]> {
  const response = await authorizedJsonFetch<string[]>(
    `/admin/playlists/${playlistId}/exclusions/${videoId}`,
    { method: 'POST' }
  );
  return response;
}

export async function removePlaylistExclusion(
  playlistId: string,
  videoId: string
): Promise<string[]> {
  const response = await authorizedJsonFetch<string[]>(
    `/admin/playlists/${playlistId}/exclusions/${videoId}`,
    { method: 'DELETE' }
  );
  return response;
}

// Workspace Exclusions

export async function fetchExclusionsPage(params: any = {}): Promise<CursorPage<any>> {
  // TODO: Implement proper backend endpoint for workspace exclusions
  // For now, return empty page
  return {
    data: [],
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function createExclusion(payload: any): Promise<any> {
  // TODO: Implement proper backend endpoint for creating exclusions
  // For now, return mock response
  console.warn('createExclusion not fully implemented - using mock response');
  return {
    id: 'mock-' + Date.now(),
    ...payload,
    createdAt: new Date().toISOString(),
    createdBy: {
      id: 'user-1',
      email: 'admin@example.com',
      displayName: 'Admin',
      roles: ['ADMIN'],
      status: 'ACTIVE',
      lastLoginAt: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    }
  };
}

export async function updateExclusion(exclusionId: string, payload: any): Promise<any> {
  // TODO: Implement proper backend endpoint for updating exclusions
  // For now, return mock response
  console.warn('updateExclusion not fully implemented - using mock response');
  return {
    id: exclusionId,
    ...payload,
    updatedAt: new Date().toISOString()
  };
}

export async function removeExclusion(exclusionId: string): Promise<void> {
  // TODO: Implement proper backend endpoint for removing exclusions
  console.warn('removeExclusion not fully implemented - using mock');
  // For now, do nothing
}
