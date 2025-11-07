import type { RouteLocationRaw } from 'vue-router';

export interface NavRoute {
  labelKey: string;
  route: RouteLocationRaw;
  children?: NavRoute[];
}

export const navRoutes: NavRoute[] = [
  { labelKey: 'navigation.dashboard', route: { name: 'dashboard' } },
  { labelKey: 'navigation.contentSearch', route: { name: 'content-search' } },
  { labelKey: 'navigation.categories', route: { name: 'categories' } },
  { labelKey: 'navigation.approvals', route: { name: 'approvals' } },
  { labelKey: 'navigation.contentLibrary', route: { name: 'content-library' } },
  { labelKey: 'navigation.exclusions', route: { name: 'exclusions' } },
  { labelKey: 'navigation.bulkImportExport', route: { name: 'bulk-import-export' } },
  { labelKey: 'navigation.videoValidation', route: { name: 'video-validation' } },
  { labelKey: 'navigation.users', route: { name: 'users' } },
  { labelKey: 'navigation.audit', route: { name: 'audit' } },
  { labelKey: 'navigation.activity', route: { name: 'activity' } },
  {
    labelKey: 'navigation.settings',
    route: { name: 'settings-profile' },
    children: [
      { labelKey: 'navigation.settingsProfile', route: { name: 'settings-profile' } },
      { labelKey: 'navigation.settingsNotifications', route: { name: 'settings-notifications' } },
      { labelKey: 'navigation.settingsYouTubeAPI', route: { name: 'settings-youtube-api' } },
      { labelKey: 'navigation.settingsSystem', route: { name: 'settings-system' } }
    ]
  }
];
