import apiClient from './api/client';

export interface CategorySortItem {
  id: string;
  name: string;
  icon: string | null;
  localizedNames: Record<string, string> | null;
  displayOrder: number;
  contentCount: number;
}

export interface ContentSortItem {
  contentId: string;
  contentType: string;
  title: string;
  thumbnailUrl: string | null;
  position: number;
  youtubeId: string | null;
}

export async function getCategorySortOrder(): Promise<CategorySortItem[]> {
  const response = await apiClient.get<CategorySortItem[]>('/api/admin/sort/categories');
  return response.data;
}

export async function reorderCategory(categoryId: string, newPosition: number): Promise<CategorySortItem[]> {
  const response = await apiClient.put<CategorySortItem[]>('/api/admin/sort/categories/reorder', {
    categoryId,
    newPosition
  });
  return response.data;
}

export async function getCategoryContentOrder(categoryId: string): Promise<ContentSortItem[]> {
  const response = await apiClient.get<ContentSortItem[]>(`/api/admin/sort/categories/${categoryId}/content`);
  return response.data;
}

export async function reorderContentInCategory(
  categoryId: string,
  contentId: string,
  contentType: string,
  newPosition: number
): Promise<ContentSortItem[]> {
  const response = await apiClient.put<ContentSortItem[]>(
    `/api/admin/sort/categories/${categoryId}/content/reorder`,
    { contentId, contentType, newPosition }
  );
  return response.data;
}
