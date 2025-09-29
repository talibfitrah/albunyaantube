import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';
import type {
  ModerationProposal,
  ModerationProposalStatus
} from '@/types/moderation';

interface ModerationListParams {
  cursor?: string | null;
  limit?: number;
  status?: ModerationProposalStatus | null;
}

const MODERATION_BASE_PATH = '/api/v1/moderation/proposals';

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

export async function fetchModerationProposals(
  params: ModerationListParams = {}
): Promise<CursorPage<ModerationProposal>> {
  const query = buildQuery({
    cursor: params.cursor ?? undefined,
    limit: params.limit,
    status: params.status ?? undefined
  });
  return authorizedJsonFetch<CursorPage<ModerationProposal>>(
    `${MODERATION_BASE_PATH}${query}`
  );
}

export async function approveModerationProposal(id: string): Promise<ModerationProposal> {
  return authorizedJsonFetch<ModerationProposal>(
    `${MODERATION_BASE_PATH}/${id}/approve`,
    { method: 'POST' }
  );
}

export async function rejectModerationProposal(
  id: string,
  reason?: string | null
): Promise<ModerationProposal> {
  const body = reason && reason.trim().length > 0 ? { reason: reason.trim() } : null;
  const init: RequestInit = { method: 'POST' };
  if (body) {
    init.body = JSON.stringify(body);
  }
  return authorizedJsonFetch<ModerationProposal>(
    `${MODERATION_BASE_PATH}/${id}/reject`,
    init
  );
}
