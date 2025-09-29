import { authorizedJsonFetch } from '@/services/http';
import type {
  ChannelSummary,
  CursorPage,
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
