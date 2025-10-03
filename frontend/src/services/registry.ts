import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';
import type {
  AdminSearchResponse,
  ChannelSummary,
  PlaylistSummary,
  VideoSummary
} from '@/types/registry';

interface PaginationParams {
  cursor?: string | null;
  limit?: number;
}

export interface ChannelListParams extends PaginationParams {
  categoryId?: string | null;
}

export interface PlaylistListParams extends PaginationParams {
  categoryId?: string | null;
}

export interface VideoListParams extends PaginationParams {
  categoryId?: string | null;
  q?: string | null;
  length?: 'SHORT' | 'MEDIUM' | 'LONG';
  date?: 'LAST_24_HOURS' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'ANYTIME';
  sort?: 'RECENT' | 'POPULAR';
}

export interface RegistrySearchParams {
  q?: string | null;
  categoryId?: string | null;
  videoLength?: VideoListParams['length'];
  videoDateRange?: VideoListParams['date'];
  videoSort?: VideoListParams['sort'];
  limit?: number;
}

// FIREBASE-MIGRATE: Updated base path from /api/v1/admins/registry to /api/admin
const CHANNELS_BASE_PATH = '/api/admin/channels';

function buildQuery(params: Record<string, string | number | null | undefined>): string {
  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      searchParams.set(key, String(value));
    }
  });
  const query = searchParams.toString();
  return query ? `?${query}` : '';
}

export async function fetchChannelsPage(params: ChannelListParams = {}): Promise<CursorPage<ChannelSummary>> {
  // FIREBASE-MIGRATE: Map to new endpoint
  let endpoint = CHANNELS_BASE_PATH;
  if (params.categoryId) {
    endpoint = `${CHANNELS_BASE_PATH}/category/${params.categoryId}`;
  }
  
  // Backend returns array, not paginated response
  // TODO: Add pagination support in backend
  const channels = await authorizedJsonFetch<any[]>(endpoint);
  
  return {
    data: channels,
    pageInfo: {
      nextCursor: null,
      hasNextPage: false
    }
  };
}

export async function fetchPlaylistsPage(params: PlaylistListParams = {}): Promise<CursorPage<PlaylistSummary>> {
  // FIREBASE-MIGRATE: Playlists not implemented in backend yet
  // TODO: Implement playlist listing in backend
  return {
    data: [],
    pageInfo: {
      nextCursor: null,
      hasNextPage: false
    }
  };
}

export async function fetchVideosPage(params: VideoListParams = {}): Promise<CursorPage<VideoSummary>> {
  // FIREBASE-MIGRATE: Videos not implemented in backend yet
  // TODO: Implement video listing in backend
  return {
    data: [],
    pageInfo: {
      nextCursor: null,
      hasNextPage: false
    }
  };
}

export async function searchRegistry(params: RegistrySearchParams = {}): Promise<AdminSearchResponse> {
  // FIREBASE-MIGRATE: Registry search not implemented in backend yet
  // The backend has YouTube search (/api/admin/youtube/search/*) but not registry search
  // TODO: Implement registry search or use YouTube search
  return {
    channels: [],
    playlists: [],
    videos: []
  };
}

export async function updateChannelExclusions(
  channelId: string,
  payload: { excludedPlaylistIds?: string[]; excludedVideoIds?: string[] }
): Promise<void> {
  // FIREBASE-MIGRATE: Updated endpoint
  await authorizedJsonFetch<void>(`${CHANNELS_BASE_PATH}/${channelId}/exclusions`, {
    method: 'PUT',  // Backend uses PUT not PATCH
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(payload)
  });
}

export async function updatePlaylistExclusions(
  playlistId: string,
  payload: { excludedVideoIds?: string[] }
): Promise<void> {
  // FIREBASE-MIGRATE: Playlist exclusions not implemented in backend yet
  // TODO: Implement playlist exclusions endpoint
  console.warn('Playlist exclusions not implemented in Firebase backend');
}
