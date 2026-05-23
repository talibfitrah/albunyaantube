import apiClient from './api/client'
import type { components } from '../generated/api/schema'

type BulkPreviewRequest  = components['schemas']['BulkPreviewRequest']
type BulkPreviewResponse = components['schemas']['BulkPreviewResponse']
type BulkSubmitRequest   = components['schemas']['BulkSubmitRequest']
type BulkSubmitResponse  = components['schemas']['BulkSubmitResponse']

// Bulk preview + submit both make N sequential NewPipe HTTP calls
// server-side (one per URL/row, up to 25). At ~1-3s per NewPipe call the
// worst-case server time is well above the global apiClient default
// (30s). Override per-request so a legitimate 25-row batch doesn't time
// out client-side while writes are still committing server-side.
//
// Backend caps the preview at 180s via CompletableFuture.allOf(...).get();
// submit is currently sequential (≤25 × ~3s each = ~75s worst case).
// Frontend matches the preview cap to absorb both with a small safety
// margin.
const BULK_TIMEOUT_MS = 200_000

/** API client for the bulk submission endpoints. */
export const bulkSubmissionService = {
  async previewBulk(req: BulkPreviewRequest): Promise<BulkPreviewResponse> {
    const { data } = await apiClient.post<BulkPreviewResponse>(
      '/api/admin/registry/bulk/preview', req, { timeout: BULK_TIMEOUT_MS })
    return data
  },
  async submitBulk(req: BulkSubmitRequest): Promise<BulkSubmitResponse> {
    const { data } = await apiClient.post<BulkSubmitResponse>(
      '/api/admin/registry/bulk/submit', req, { timeout: BULK_TIMEOUT_MS })
    return data
  },
}
