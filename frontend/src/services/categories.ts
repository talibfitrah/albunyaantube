import { authorizedJsonFetch } from '@/services/http';

// FIREBASE-MIGRATE: Updated to match new backend Category model
interface CategoryResponse {
  id: string;
  name: string;  // Simple string, not localized map
  parentCategoryId: string | null;
  icon: string | null;
  displayOrder: number | null;
  localizedNames: Record<string, string> | null;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}

export interface CategoryOption {
  id: string;
  slug: string;  // Using ID as slug for now
  label: string;
  subcategories?: CategoryOption[];
}

export async function fetchAllCategories(limit = 100): Promise<CategoryOption[]> {
  // FIREBASE-MIGRATE: Updated endpoint from /api/v1/admins/categories to /api/admin/categories
  const categories = await authorizedJsonFetch<CategoryResponse[]>(
    `/api/admin/categories`
  );

  // Build hierarchical structure from flat list
  return buildHierarchy(categories);
}

function buildHierarchy(categories: CategoryResponse[]): CategoryOption[] {
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

function resolveLabel(category: CategoryResponse): string {
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
