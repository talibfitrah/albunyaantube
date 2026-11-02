/**
 * Content Validation Service
 *
 * Provides API calls for content validation (channels, playlists, videos)
 * and archived content review functionality.
 */

import apiClient from './api/client';
import type {
  ValidationRun,
  AsyncValidationResponse,
  ArchivedContent,
  ArchivedCounts,
  ContentActionRequest,
  ContentActionResult,
  ContentType,
  TriggerValidationOptions
} from '@/types/validation';

// Backend routes are exposed under /api/admin/content-validation (CORS-enabled)
const BASE_PATH = '/api/admin/content-validation';

// ==================== Validation Triggers ====================

/**
 * Start async validation of all content types (channels, playlists, videos).
 * Returns immediately with a run ID for polling progress.
 */
export async function validateAllContent(
  options?: TriggerValidationOptions
): Promise<AsyncValidationResponse> {
  const params = options?.maxItems ? { maxItems: options.maxItems } : {};

  const response = await apiClient.post<AsyncValidationResponse>(
    `${BASE_PATH}/validate/all`,
    null,
    { params }
  );

  return response.data;
}

/**
 * Get validation run status by ID (for progress polling)
 */
export async function getValidationStatus(runId: string): Promise<ValidationRun> {
  const response = await apiClient.get<ValidationRun>(
    `${BASE_PATH}/status/${runId}`
  );

  return response.data;
}

// ==================== Archived Content ====================

/**
 * Get counts of archived content by type
 */
export async function getArchivedCounts(): Promise<ArchivedCounts> {
  const response = await apiClient.get<ArchivedCounts>(
    `${BASE_PATH}/archived/counts`
  );

  return response.data;
}

/**
 * Get archived content by type
 */
export async function getArchivedContent(
  type: ContentType
): Promise<ArchivedContent[]> {
  const endpoint = type.toLowerCase() + 's'; // channels, playlists, videos

  const response = await apiClient.get<{ data: ArchivedContent[]; count: number }>(
    `${BASE_PATH}/archived/${endpoint}`
  );

  return response.data.data;
}

/**
 * Get all archived channels
 */
export async function getArchivedChannels(): Promise<ArchivedContent[]> {
  return getArchivedContent('CHANNEL');
}

/**
 * Get all archived playlists
 */
export async function getArchivedPlaylists(): Promise<ArchivedContent[]> {
  return getArchivedContent('PLAYLIST');
}

/**
 * Get all archived videos
 */
export async function getArchivedVideos(): Promise<ArchivedContent[]> {
  return getArchivedContent('VIDEO');
}

// ==================== Content Actions ====================

/**
 * Perform bulk action on archived content (delete or restore)
 */
async function performBulkAction(
  request: ContentActionRequest
): Promise<ContentActionResult> {
  const response = await apiClient.post<ContentActionResult>(
    `${BASE_PATH}/action`,
    request
  );

  return response.data;
}

/**
 * Delete archived content permanently
 */
export async function deleteArchivedContent(
  type: ContentType,
  ids: string[],
  reason?: string
): Promise<ContentActionResult> {
  return performBulkAction({
    action: 'DELETE',
    type,
    ids,
    reason
  });
}

/**
 * Restore archived content to active status
 */
export async function restoreArchivedContent(
  type: ContentType,
  ids: string[],
  reason?: string
): Promise<ContentActionResult> {
  return performBulkAction({
    action: 'RESTORE',
    type,
    ids,
    reason
  });
}
