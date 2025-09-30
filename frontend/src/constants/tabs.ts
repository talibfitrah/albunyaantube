export type MainTabKey = 'home' | 'channels' | 'playlists' | 'videos';

export type TabIconId = 'home' | 'channels' | 'playlists' | 'videos';

export interface TabDefinition {
  key: MainTabKey;
  labelKey: string;
  icon: TabIconId;
  routeName: string;
}

export const MAIN_TABS: Readonly<TabDefinition[]> = Object.freeze([
  { key: 'home', labelKey: 'navigation.home', icon: 'home', routeName: 'home' },
  { key: 'channels', labelKey: 'navigation.channels', icon: 'channels', routeName: 'channels' },
  { key: 'playlists', labelKey: 'navigation.playlists', icon: 'playlists', routeName: 'playlists' },
  { key: 'videos', labelKey: 'navigation.videos', icon: 'videos', routeName: 'videos' }
]);

export const TAB_ICON_PATHS: Record<TabIconId, string> = Object.freeze({
  home: 'M12 2.5 3 10.4v9.1c0 .55.45 1 1 1h5.6v-5.6h4.8v5.6H20c.55 0 1-.45 1-1v-9.1L12 2.5Z',
  channels: 'M5 5h14c.55 0 1 .45 1 1v12c0 .55-.45 1-1 1H5c-.55 0-1-.45-1-1V6c0-.55.45-1 1-1Zm2.5 3.5v7l6-3.5-6-3.5Z',
  playlists: 'M4.5 6.5h11a1 1 0 0 1 0 2h-11a1 1 0 0 1 0-2Zm0 4.5h11a1 1 0 0 1 0 2h-11a1 1 0 0 1 0-2Zm0 4.5h6a1 1 0 0 1 0 2h-6a1 1 0 0 1 0-2Zm12.5-10.5h3v12h-3z',
  videos: 'M6 5.5h8c.55 0 1 .45 1 1v1.9l3.4-1.95c.66-.38 1.6.04 1.6.83v8.34c0 .79-.94 1.21-1.6.83L15 14.5v1.9c0 .55-.45 1-1 1H6a1 1 0 0 1-1-1v-10c0-.55.45-1 1-1Z'
});
