import apiClient from './api/client'
import type { components } from '../generated/api/schema'

type BulkPreviewRequest  = components['schemas']['BulkPreviewRequest']
type BulkPreviewResponse = components['schemas']['BulkPreviewResponse']
type BulkSubmitRequest   = components['schemas']['BulkSubmitRequest']
type BulkSubmitResponse  = components['schemas']['BulkSubmitResponse']

/** API client for the bulk submission endpoints. */
export const bulkSubmissionService = {
  async previewBulk(req: BulkPreviewRequest): Promise<BulkPreviewResponse> {
    const { data } = await apiClient.post<BulkPreviewResponse>('/api/admin/registry/bulk/preview', req)
    return data
  },
  async submitBulk(req: BulkSubmitRequest): Promise<BulkSubmitResponse> {
    const { data } = await apiClient.post<BulkSubmitResponse>('/api/admin/registry/bulk/submit', req)
    return data
  },
}
