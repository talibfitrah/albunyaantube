import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';
import type {
  AdminSearchResponse,
  ChannelSummary,
  PlaylistSummary,
  VideoSummary
} from '@/types/registry';
import type { Channel } from '@/types/api';

interface PaginationParams {
  cursor?: string | null;
  limit?: number;
}

/**
 * Map API Channel DTO to UI ChannelSummary model
 */
function mapChannelToSummary(channel: Channel): ChannelSummary {
  return {
    id: channel.id,
    ytId: channel.youtubeId,
    name: channel.name,
    avatarUrl: channel.thumbnailUrl || null,
    subscriberCount: channel.subscribers || 0,
    categories: [] // CategoryTag mapping would require category lookup
  };
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
  const channels = await authorizedJsonFetch<Channel[]>(endpoint);

  return {
    data: channels.map(mapChannelToSummary),
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function fetchPlaylistsPage(params: PlaylistListParams = {}): Promise<CursorPage<PlaylistSummary>> {
  // FIREBASE-MIGRATE: Playlists not implemented in backend yet
  // TODO: Implement playlist listing in backend
  return {
    data: [],
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function fetchVideosPage(params: VideoListParams = {}): Promise<CursorPage<VideoSummary>> {
  // FIREBASE-MIGRATE: Videos not implemented in backend yet
  // TODO: Implement video listing in backend
  return {
    data: [],
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function searchRegistry(params: RegistrySearchParams = {}): Promise<AdminSearchResponse> {
  // FIREBASE-MIGRATE: Registry search not implemented in backend yet
  // The backend has YouTube search (/api/admin/youtube/search/*) but not registry search
  // TODO: Implement registry search or use YouTube search
  return {
    query: params.q || '',
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
