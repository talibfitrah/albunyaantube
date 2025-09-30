export interface CategoryTag {
  id: string;
  label: string;
}

export interface ChannelSummary {
  id: string;
  ytId: string;
  name: string | null;
  avatarUrl: string | null;
  subscriberCount: number;
  categories: CategoryTag[];
}

export interface PlaylistSummary {
  id: string;
  ytId: string;
  title: string | null;
  thumbnailUrl: string | null;
  itemCount: number;
  owner: ChannelSummary;
  categories: CategoryTag[];
  downloadable?: boolean;
}

export interface VideoSummary {
  id: string;
  ytId: string;
  title: string | null;
  thumbnailUrl: string | null;
  durationSeconds: number;
  publishedAt: string;
  viewCount: number;
  channel: ChannelSummary;
  categories: CategoryTag[];
  bookmarked?: boolean;
  downloaded?: boolean;
}

export type IncludeState = 'NOT_INCLUDED' | 'INCLUDED' | 'EXCLUDED';

export interface ExcludedItemCounts {
  videos: number;
  playlists: number;
}

export interface AdminSearchChannelResult {
  id: string;
  ytId: string;
  name: string | null;
  avatarUrl: string | null;
  subscriberCount: number;
  categories: CategoryTag[];
  includeState: IncludeState;
  excludedItemCounts: ExcludedItemCounts;
  excludedPlaylistIds: string[];
  excludedVideoIds: string[];
  bulkEligible: boolean;
}

export interface AdminSearchPlaylistResult {
  id: string;
  ytId: string;
  title: string | null;
  thumbnailUrl: string | null;
  itemCount: number;
  owner: ChannelSummary;
  categories: CategoryTag[];
  downloadable: boolean;
  includeState: IncludeState;
  parentChannelId: string;
  excludedVideoCount: number;
  excludedVideoIds: string[];
  bulkEligible: boolean;
}

export interface AdminSearchVideoResult {
  id: string;
  ytId: string;
  title: string | null;
  thumbnailUrl: string | null;
  durationSeconds: number;
  publishedAt: string;
  viewCount: number;
  channel: ChannelSummary;
  categories: CategoryTag[];
  bookmarked: boolean | null;
  downloaded: boolean | null;
  includeState: IncludeState;
  parentChannelId: string;
  parentPlaylistIds: string[];
}

export interface AdminSearchResponse {
  query: string;
  channels: AdminSearchChannelResult[];
  playlists: AdminSearchPlaylistResult[];
  videos: AdminSearchVideoResult[];
}
