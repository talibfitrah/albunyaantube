import apiClient from './api/client';
import type {
  ValidationRun,
  ValidationRunResponse,
  TriggerValidationOptions
} from '@/types/validation';

const BASE_PATH = '/api/admin/videos';

/**
 * Trigger a manual video validation run
 */
export async function triggerValidation(
  options?: TriggerValidationOptions
): Promise<ValidationRunResponse> {
  const params = options?.maxVideos ? { maxVideos: options.maxVideos } : {};

  const response = await apiClient.post<ValidationRunResponse>(`${BASE_PATH}/validate`, null, {
    params
  });

  return response.data;
}

/**
 * Get validation run status by ID
 */
export async function getValidationStatus(runId: string): Promise<ValidationRun> {
  const response = await apiClient.get<ValidationRun>(`${BASE_PATH}/validation-status/${runId}`);
  return response.data;
}

/**
 * Get validation history (last N runs)
 */
export async function getValidationHistory(limit: number = 20): Promise<ValidationRun[]> {
  const response = await apiClient.get<{ runs: ValidationRun[]; count: number }>(
    `${BASE_PATH}/validation-history`,
    {
      params: { limit }
    }
  );

  return response.data.runs;
}

/**
 * Get the latest validation run
 */
export async function getLatestValidationRun(): Promise<ValidationRun | null> {
  const response = await apiClient.get<ValidationRun | { message: string }>(
    `${BASE_PATH}/validation-latest`
  );

  // Handle case where no validation runs exist
  if ('message' in response.data) {
    return null;
  }

  return response.data;
}
