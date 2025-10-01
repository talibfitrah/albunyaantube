import type { RouteLocationRaw } from 'vue-router';

export interface NavRoute {
  labelKey: string;
  route: RouteLocationRaw;
}

export const navRoutes: NavRoute[] = [
  { labelKey: 'navigation.dashboard', route: { name: 'dashboard' } },
  { labelKey: 'navigation.registry', route: { name: 'registry' } },
  { labelKey: 'navigation.exclusions', route: { name: 'exclusions' } },
  { labelKey: 'navigation.moderation', route: { name: 'moderation' } },
  { labelKey: 'navigation.users', route: { name: 'users' } },
  { labelKey: 'navigation.audit', route: { name: 'audit' } }
];
