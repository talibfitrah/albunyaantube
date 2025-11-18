/**
 * Category Service
 * Real backend API integration for category management
 */

import apiClient from './api/client';
import { toast } from '@/utils/toast';

import type { Category as ApiCategory } from '@/types/api';

// Re-export API type with additional UI fields
export interface Category extends ApiCategory {
  label?: string; // UI display field (derived from name)
  subcategories?: Category[];
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

  // First pass: create map and add UI fields
  categories.forEach(cat => {
    const normalized: Category = {
      ...cat,
      label: cat.name, // UI display field
      subcategories: []
    };
    categoryMap.set(cat.id, normalized);
  });

  // Second pass: build tree
  categories.forEach(cat => {
    const normalized = categoryMap.get(cat.id)!;
    const parentId = cat.parentCategoryId;

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
  displayOrder?: number;
}): Promise<Category> {
  const payload = {
    name: data.name,
    parentCategoryId: data.parentId || null,
    icon: data.icon || '',
    displayOrder: data.displayOrder || 0
  };

  const response = await apiClient.post<Category>('/api/admin/categories', payload);
  toast.success(`Category "${data.name}" created successfully`);
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
  }
): Promise<Category> {
  const response = await apiClient.put<Category>(`/api/admin/categories/${id}`, data);
  toast.success('Category updated successfully');
  return response.data;
}

/**
 * Delete a category
 */
export async function deleteCategory(id: string): Promise<void> {
  try {
    await apiClient.delete(`/api/admin/categories/${id}`);
    toast.success('Category deleted successfully');
  } catch (error: any) {
    if (error.response?.status === 409) {
      toast.error('Cannot delete category with subcategories');
      throw new Error('Category has subcategories');
    }
    throw error;
  }
}
