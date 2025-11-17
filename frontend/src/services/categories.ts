import { authorizedJsonFetch } from '@/services/http';
import type { Category } from '@/types/api';

export interface CategoryOption {
  id: string;
  slug: string;  // Using ID as slug for now
  label: string;
  subcategories?: CategoryOption[];
}

export async function fetchAllCategories(limit = 100): Promise<CategoryOption[]> {
  // FIREBASE-MIGRATE: Updated endpoint from /api/v1/admins/categories to /api/admin/categories
  const categories = await authorizedJsonFetch<Category[]>(
    `/api/admin/categories`
  );

  // Build hierarchical structure from flat list
  return buildHierarchy(categories);
}

function buildHierarchy(categories: Category[]): CategoryOption[] {
  const categoryMap = new Map<string, CategoryOption>();
  const rootCategories: CategoryOption[] = [];

  // First pass: create CategoryOption for each category
  categories.forEach(cat => {
    categoryMap.set(cat.id, {
      id: cat.id,
      slug: cat.id,  // Using ID as slug since backend doesn't have slug field
      label: resolveLabel(cat),
      subcategories: []
    });
  });

  // Second pass: build hierarchy
  categories.forEach(cat => {
    const categoryOption = categoryMap.get(cat.id)!;
    if (cat.parentCategoryId) {
      const parent = categoryMap.get(cat.parentCategoryId);
      if (parent) {
        parent.subcategories!.push(categoryOption);
      }
    } else {
      rootCategories.push(categoryOption);
    }
  });

  return rootCategories;
}

function resolveLabel(category: Category): string {
  // Try localized names first
  if (category.localizedNames) {
    if (category.localizedNames.en) {
      return category.localizedNames.en;
    }
    const firstEntry = Object.values(category.localizedNames)[0];
    if (firstEntry) {
      return firstEntry;
    }
  }
  // Fallback to name field
  return category.name || '';
}
