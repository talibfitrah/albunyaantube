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

const REGISTRY_BASE_PATH = '/api/v1/admins/registry';

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
  const query = buildQuery({
    cursor: params.cursor ?? undefined,
    limit: params.limit,
    categoryId: params.categoryId ?? undefined
  });
  return authorizedJsonFetch<CursorPage<ChannelSummary>>(`${REGISTRY_BASE_PATH}/channels${query}`);
}

export async function fetchPlaylistsPage(params: PlaylistListParams = {}): Promise<CursorPage<PlaylistSummary>> {
  const query = buildQuery({
    cursor: params.cursor ?? undefined,
    limit: params.limit,
    categoryId: params.categoryId ?? undefined
  });
  return authorizedJsonFetch<CursorPage<PlaylistSummary>>(`${REGISTRY_BASE_PATH}/playlists${query}`);
}

export async function fetchVideosPage(params: VideoListParams = {}): Promise<CursorPage<VideoSummary>> {
  const query = buildQuery({
    cursor: params.cursor ?? undefined,
    limit: params.limit,
    categoryId: params.categoryId ?? undefined,
    q: params.q ?? undefined,
    length: params.length,
    date: params.date,
    sort: params.sort
  });
  return authorizedJsonFetch<CursorPage<VideoSummary>>(`${REGISTRY_BASE_PATH}/videos${query}`);
}

export async function searchRegistry(params: RegistrySearchParams = {}): Promise<AdminSearchResponse> {
  const query = buildQuery({
    q: params.q ?? undefined,
    categoryId: params.categoryId ?? undefined,
    videoLength: params.videoLength ?? undefined,
    videoDateRange: params.videoDateRange ?? undefined,
    videoSort: params.videoSort ?? undefined,
    limit: params.limit
  });
  return authorizedJsonFetch<AdminSearchResponse>(`${REGISTRY_BASE_PATH}/search${query}`);
}

export async function updateChannelExclusions(
  channelId: string,
  payload: { excludedPlaylistIds?: string[]; excludedVideoIds?: string[] }
): Promise<void> {
  await authorizedJsonFetch<void>(`${REGISTRY_BASE_PATH}/channels/${channelId}/exclusions`, {
    method: 'PATCH',
    body: JSON.stringify({
      excludedPlaylistIds: payload.excludedPlaylistIds ?? [],
      excludedVideoIds: payload.excludedVideoIds ?? []
    })
  });
}

export async function updatePlaylistExclusions(
  playlistId: string,
  payload: { excludedVideoIds?: string[] }
): Promise<void> {
  await authorizedJsonFetch<void>(`${REGISTRY_BASE_PATH}/playlists/${playlistId}/exclusions`, {
    method: 'PATCH',
    body: JSON.stringify({
      excludedVideoIds: payload.excludedVideoIds ?? []
    })
  });
}
