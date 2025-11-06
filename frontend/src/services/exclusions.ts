import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

// Channel Exclusions

export async function fetchChannelExclusions(channelId: string): Promise<any> {
  const response = await authorizedJsonFetch(`/admin/channels/${channelId}/exclusions`);
  return response;
}

export async function addChannelExclusion(
  channelId: string,
  type: string,
  youtubeId: string
): Promise<any> {
  const response = await authorizedJsonFetch(
    `/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`,
    { method: 'POST' }
  );
  return response;
}

export async function removeChannelExclusion(
  channelId: string,
  type: string,
  youtubeId: string
): Promise<any> {
  const response = await authorizedJsonFetch(
    `/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`,
    { method: 'DELETE' }
  );
  return response;
}

// Playlist Exclusions

export async function fetchPlaylistExclusions(playlistId: string): Promise<string[]> {
  const response = await authorizedJsonFetch(`/admin/playlists/${playlistId}/exclusions`);
  return response;
}

export async function addPlaylistExclusion(
  playlistId: string,
  videoId: string
): Promise<string[]> {
  const response = await authorizedJsonFetch(
    `/admin/playlists/${playlistId}/exclusions/${videoId}`,
    { method: 'POST' }
  );
  return response;
}

export async function removePlaylistExclusion(
  playlistId: string,
  videoId: string
): Promise<string[]> {
  const response = await authorizedJsonFetch(
    `/admin/playlists/${playlistId}/exclusions/${videoId}`,
    { method: 'DELETE' }
  );
  return response;
}

// Legacy function for backward compatibility
export async function fetchExclusionsPage(params: any = {}): Promise<CursorPage<any>> {
  // This function is not used in the new implementation
  return {
    data: [],
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function removeExclusion(exclusionId: string): Promise<void> {
  // This function is not used in the new implementation
  console.warn('Use removeChannelExclusion or removePlaylistExclusion instead');
}
