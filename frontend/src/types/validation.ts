export interface ValidationRun {
  id: string;
  triggerType: 'SCHEDULED' | 'MANUAL' | 'IMPORT' | 'EXPORT';
  triggeredBy?: string | null;
  triggeredByDisplayName?: string | null;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  // Legacy video-only fields
  videosChecked: number;
  videosMarkedUnavailable: number;
  // New comprehensive fields
  channelsChecked?: number;
  channelsArchived?: number;
  playlistsChecked?: number;
  playlistsArchived?: number;
  videosArchived?: number;
  totalChecked?: number;
  totalArchived?: number;
  errorCount: number;
  details?: Record<string, any>;
  startedAt: string;
  completedAt?: string | null;
  durationMs?: number | null;
  // Progress tracking fields
  totalChannelsToCheck?: number;
  totalPlaylistsToCheck?: number;
  totalVideosToCheck?: number;
  totalToCheck?: number;
  progressPercent?: number;
  currentPhase?: 'STARTING' | 'INITIALIZING' | 'CHANNELS' | 'PLAYLISTS' | 'VIDEOS' | 'COMPLETE';
}

export interface ValidationRunResponse {
  success: boolean;
  message: string;
  data: ValidationRun;
}

/**
 * Response when starting async validation
 */
export interface AsyncValidationResponse {
  success: boolean;
  message: string;
  runId: string;
  status: 'RUNNING';
}

export interface TriggerValidationOptions {
  maxVideos?: number;
  maxItems?: number;
}

// Content validation types
export type ContentType = 'CHANNEL' | 'PLAYLIST' | 'VIDEO';
export type ContentAction = 'DELETE' | 'RESTORE';

export interface ArchivedContent {
  id: string;
  type: ContentType;
  youtubeId: string;
  title: string;
  thumbnailUrl?: string | null;
  category?: string | null;
  archivedAt?: string | null;
  lastValidatedAt?: string | null;
  metadata?: string | null;
}

export interface ArchivedCounts {
  channels: number;
  playlists: number;
  videos: number;
  total: number;
}

export interface ContentActionRequest {
  action: ContentAction;
  type: ContentType;
  ids: string[];
  reason?: string;
}

export interface ContentActionResult {
  action: ContentAction;
  type: ContentType;
  successCount: number;
  failureCount: number;
  failedIds?: string[];
  message: string;
}
