import { authorizedJsonFetch } from '@/services/http';
import type {
  CreateExclusionPayload,
  Exclusion,
  ExclusionPage,
  UpdateExclusionPayload
} from '@/types/exclusions';

const EXCLUSIONS_BASE_PATH = '/api/v1/exclusions';

export interface ExclusionListParams {
  cursor?: string | null;
  limit?: number;
  parentType?: string | null;
  excludeType?: string | null;
  search?: string | null;
}

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

export async function fetchExclusionsPage(params: ExclusionListParams = {}): Promise<ExclusionPage> {
  const query = buildQuery({
    cursor: params.cursor ?? undefined,
    limit: params.limit,
    parentType: params.parentType ?? undefined,
    excludeType: params.excludeType ?? undefined,
    search: params.search ?? undefined
  });
  return authorizedJsonFetch<ExclusionPage>(`${EXCLUSIONS_BASE_PATH}${query}`);
}

export async function createExclusion(payload: CreateExclusionPayload): Promise<Exclusion> {
  return authorizedJsonFetch<Exclusion>(EXCLUSIONS_BASE_PATH, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export async function updateExclusion(
  id: string,
  payload: UpdateExclusionPayload
): Promise<Exclusion> {
  return authorizedJsonFetch<Exclusion>(`${EXCLUSIONS_BASE_PATH}/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export async function deleteExclusion(id: string): Promise<void> {
  await authorizedJsonFetch<void>(`${EXCLUSIONS_BASE_PATH}/${id}`, {
    method: 'DELETE'
  });
}
