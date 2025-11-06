export interface ValidationRun {
  id: string;
  triggerType: 'SCHEDULED' | 'MANUAL' | 'IMPORT' | 'EXPORT';
  triggeredBy?: string | null;
  triggeredByDisplayName?: string | null;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  videosChecked: number;
  videosMarkedUnavailable: number;
  errorCount: number;
  details?: Record<string, any>;
  startedAt: string;
  completedAt?: string | null;
  durationMs?: number | null;
}

export interface ValidationRunResponse {
  success: boolean;
  message: string;
  data: ValidationRun;
}

export interface TriggerValidationOptions {
  maxVideos?: number;
}
