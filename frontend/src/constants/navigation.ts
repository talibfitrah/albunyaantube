import type { RouteLocationRaw } from 'vue-router';

export interface NavRoute {
  labelKey: string;
  route: RouteLocationRaw;
}

export const navRoutes: NavRoute[] = [
  { labelKey: 'navigation.dashboard', route: { name: 'dashboard' } },
  { labelKey: 'navigation.contentSearch', route: { name: 'content-search' } },
  { labelKey: 'navigation.categories', route: { name: 'categories' } },
  { labelKey: 'navigation.users', route: { name: 'users' } },
  { labelKey: 'navigation.audit', route: { name: 'audit' } }
];
