import { authorizedJsonFetch } from '@/services/http';
import type { BulkActionItem, BulkActionResponse } from '@/types/api';

/**
 * Bulk approve content items
 */
export async function bulkApprove(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/api/admin/content/bulk/approve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk reject content items
 */
export async function bulkReject(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/api/admin/content/bulk/reject', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk delete content items
 */
export async function bulkDelete(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/api/admin/content/bulk/delete', {
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
  return await authorizedJsonFetch('/api/admin/content/bulk/assign-categories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items, categoryIds })
  });
}
