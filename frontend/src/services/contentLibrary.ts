import { authorizedJsonFetch } from '@/services/http';

export interface BulkActionItem {
  type: string;
  id: string;
}

export interface BulkActionResponse {
  successCount: number;
  errors: string[];
}

/**
 * Bulk approve content items
 */
export async function bulkApprove(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/approve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk reject content items
 */
export async function bulkReject(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/reject', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk delete content items
 */
export async function bulkDelete(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk assign categories to content items
 */
export async function bulkAssignCategories(
  items: BulkActionItem[],
  categoryIds: string[]
): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/assign-categories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items, categoryIds })
  });
}
