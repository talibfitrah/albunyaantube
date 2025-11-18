import apiClient from './api/client';
import type { BulkActionItem, BulkActionResponse } from '@/types/api';

/**
 * Bulk approve content items
 */
export async function bulkApprove(items: BulkActionItem[]): Promise<BulkActionResponse> {
  const response = await apiClient.post<BulkActionResponse>('/api/admin/content/bulk/approve', { items });
  return response.data;
}

/**
 * Bulk reject content items
 */
export async function bulkReject(items: BulkActionItem[]): Promise<BulkActionResponse> {
  const response = await apiClient.post<BulkActionResponse>('/api/admin/content/bulk/reject', { items });
  return response.data;
}

/**
 * Bulk delete content items
 */
export async function bulkDelete(items: BulkActionItem[]): Promise<BulkActionResponse> {
  const response = await apiClient.post<BulkActionResponse>('/api/admin/content/bulk/delete', { items });
  return response.data;
}

/**
 * Bulk assign categories to content items
 */
export async function bulkAssignCategories(
  items: BulkActionItem[],
  categoryIds: string[]
): Promise<BulkActionResponse> {
  const response = await apiClient.post<BulkActionResponse>('/api/admin/content/bulk/assign-categories', { items, categoryIds });
  return response.data;
}
