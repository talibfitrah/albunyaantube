export interface CategoryTag {
  id: string;
  label: string;
}

export interface ChannelSummary {
  id: string;
  ytId: string;
  name: string;
  avatarUrl: string;
  subscriberCount: number;
  categories: CategoryTag[];
}

export interface PlaylistSummary {
  id: string;
  ytId: string;
  title: string;
  thumbnailUrl: string;
  itemCount: number;
  owner: ChannelSummary;
  categories: CategoryTag[];
  downloadable?: boolean;
}

export interface VideoSummary {
  id: string;
  ytId: string;
  title: string;
  thumbnailUrl: string;
  durationSeconds: number;
  publishedAt: string;
  viewCount: number;
  channel: ChannelSummary;
  categories: CategoryTag[];
  bookmarked?: boolean;
  downloaded?: boolean;
}
