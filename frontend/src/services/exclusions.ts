import { authorizedJsonFetch } from '@/services/http';
import type { CreateExclusionPayload, Exclusion } from '@/types/exclusions';

const EXCLUSIONS_BASE_PATH = '/api/v1/exclusions';

export async function listExclusions(): Promise<Exclusion[]> {
  return authorizedJsonFetch<Exclusion[]>(EXCLUSIONS_BASE_PATH);
}

export async function createExclusion(payload: CreateExclusionPayload): Promise<Exclusion> {
  return authorizedJsonFetch<Exclusion>(EXCLUSIONS_BASE_PATH, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export async function deleteExclusion(id: string): Promise<void> {
  await authorizedJsonFetch<void>(`${EXCLUSIONS_BASE_PATH}/${id}`, {
    method: 'DELETE'
  });
}
