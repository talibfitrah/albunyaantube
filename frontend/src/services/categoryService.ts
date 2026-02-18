/**
 * Category Service
 * Real backend API integration for category management
 */

import apiClient from './api/client';

export interface Category {
  id: string;
  name?: string; // Backend uses 'name'
  label?: string; // Frontend uses 'label' (for compatibility)
  parentCategoryId?: string | null;
  parentId?: string | null; // Alias for parentCategoryId
  icon?: string;
  displayOrder?: number;
  subcategories?: Category[];
  localizedNames?: Record<string, string>;
  createdBy?: string;
  updatedBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Get all categories (hierarchical tree)
 */
export async function getAllCategories(): Promise<Category[]> {
  const response = await apiClient.get<Category[]>('/api/admin/categories');
  const categories = response.data;

  // Build hierarchical tree
  const categoryMap = new Map<string, Category>();
  const rootCategories: Category[] = [];

  // First pass: create map and normalize field names
  categories.forEach(cat => {
    const normalized = {
      ...cat,
      label: cat.name || cat.label,
      parentId: cat.parentCategoryId || cat.parentId,
      subcategories: []
    };
    categoryMap.set(cat.id, normalized);
  });

  // Second pass: build tree
  categories.forEach(cat => {
    const normalized = categoryMap.get(cat.id)!;
    const parentId = cat.parentCategoryId || cat.parentId;

    if (parentId) {
      const parent = categoryMap.get(parentId);
      if (parent) {
        if (!parent.subcategories) {
          parent.subcategories = [];
        }
        parent.subcategories.push(normalized);
      }
    } else {
      rootCategories.push(normalized);
    }
  });

  return rootCategories;
}

/**
 * Create a new category
 */
export async function createCategory(data: {
  name: string;
  parentId?: string | null;
  icon?: string;
  displayOrder?: number | null;
  localizedNames?: Record<string, string>;
}): Promise<Category> {
  const payload: Record<string, any> = {
    name: data.name,
    parentCategoryId: data.parentId || null,
    icon: data.icon || ''
  };
  // Only include displayOrder when explicitly set (not null/undefined).
  // Omitting it lets the backend auto-assign max+1.
  if (data.displayOrder !== null && data.displayOrder !== undefined) {
    payload.displayOrder = data.displayOrder;
  }
  if (data.localizedNames && Object.keys(data.localizedNames).length > 0) {
    payload.localizedNames = data.localizedNames;
  }

  const response = await apiClient.post<Category>('/api/admin/categories', payload);
  return response.data;
}

/**
 * Update an existing category
 */
export async function updateCategory(
  id: string,
  data: {
    name?: string;
    icon?: string;
    displayOrder?: number;
    localizedNames?: Record<string, string>;
  }
): Promise<Category> {
  const response = await apiClient.put<Category>(`/api/admin/categories/${id}`, data);
  return response.data;
}

/**
 * Delete a category
 */
export async function deleteCategory(id: string): Promise<void> {
  try {
    await apiClient.delete(`/api/admin/categories/${id}`);
  } catch (error: any) {
    if (error.response?.status === 409) {
      throw new Error('Category has subcategories');
    }
    throw error;
  }
}
