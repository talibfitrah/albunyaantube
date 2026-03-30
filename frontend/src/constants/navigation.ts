import type { RouteLocationRaw } from 'vue-router';
import type { UserRole } from '@/stores/auth';

export interface NavRoute {
  labelKey: string;
  route: RouteLocationRaw;
  children?: NavRoute[];
  /** Minimum role required. If not set, any authenticated user can access. */
  requiredRole?: UserRole;
}

export const navRoutes: NavRoute[] = [
  { labelKey: 'navigation.dashboard', route: { name: 'dashboard' } },
  { labelKey: 'navigation.contentSearch', route: { name: 'content-search' } },
  { labelKey: 'navigation.categories', route: { name: 'categories' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.approvals', route: { name: 'approvals' } },
  { labelKey: 'navigation.contentLibrary', route: { name: 'content-library' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.contentSorting', route: { name: 'content-sorting' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.exclusions', route: { name: 'exclusions' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.bulkImportExport', route: { name: 'bulk-import-export' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.archivedContent', route: { name: 'archived-content' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.users', route: { name: 'users' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.audit', route: { name: 'audit' }, requiredRole: 'ADMIN' },
  { labelKey: 'navigation.activity', route: { name: 'activity' }, requiredRole: 'ADMIN' },
  {
    labelKey: 'navigation.settings',
    route: { name: 'settings-profile' },
    children: [
      { labelKey: 'navigation.settingsProfile', route: { name: 'settings-profile' } },
      { labelKey: 'navigation.settingsNotifications', route: { name: 'settings-notifications' } },
      { labelKey: 'navigation.settingsSystem', route: { name: 'settings-system' }, requiredRole: 'ADMIN' }
    ]
  }
];
