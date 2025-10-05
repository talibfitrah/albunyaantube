/**
 * Mock YouTube Service
 * Replace with real backend API calls when BACKEND-REG-01 is complete.
 */

import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult, CategoryTag } from '@/types/registry';

const mockCategories: CategoryTag[] = [
  { id: 'cat-1', label: 'Tafsir' },
  { id: 'cat-2', label: 'Fiqh' },
  { id: 'cat-3', label: 'Aqeedah' }
];

const mockChannels: AdminSearchChannelResult[] = [
  {
    id: 'ch-1',
    ytId: 'UC_mock_1',
    name: 'Islamic Lectures Channel',
    avatarUrl: 'https://via.placeholder.com/200',
    subscriberCount: 150000,
    categories: [mockCategories[0]],
    includeState: 'NOT_INCLUDED',
    excludedItemCounts: { videos: 0, playlists: 0 },
    excludedPlaylistIds: [],
    excludedVideoIds: [],
    bulkEligible: true
  },
  {
    id: 'ch-2',
    ytId: 'UC_mock_2',
    name: 'Quran Tafsir Series',
    avatarUrl: 'https://via.placeholder.com/200',
    subscriberCount: 250000,
    categories: [mockCategories[0]],
    includeState: 'INCLUDED',
    excludedItemCounts: { videos: 5, playlists: 1 },
    excludedPlaylistIds: [],
    excludedVideoIds: [],
    bulkEligible: false
  }
];

const mockPlaylists: AdminSearchPlaylistResult[] = [
  {
    id: 'pl-1',
    ytId: 'PL_mock_1',
    title: 'Complete Tafsir Al-Baqarah',
    thumbnailUrl: 'https://via.placeholder.com/320x180',
    itemCount: 45,
    owner: mockChannels[0] as any,
    categories: [mockCategories[0]],
    downloadable: true,
    includeState: 'NOT_INCLUDED',
    parentChannelId: 'ch-1',
    excludedVideoCount: 0,
    excludedVideoIds: [],
    bulkEligible: true
  }
];

const mockVideos: AdminSearchVideoResult[] = [
  {
    id: 'v-1',
    ytId: 'mock_video_1',
    title: 'Understanding Surah Al-Fatiha',
    thumbnailUrl: 'https://via.placeholder.com/320x180',
    durationSeconds: 3600,
    publishedAt: '2024-09-15T10:00:00Z',
    viewCount: 45000,
    channel: mockChannels[0] as any,
    categories: [mockCategories[0]],
    bookmarked: null,
    downloaded: null,
    includeState: 'NOT_INCLUDED',
    parentChannelId: 'ch-1',
    parentPlaylistIds: ['pl-1']
  }
];

export async function searchYouTube(query: string, type: 'channels' | 'playlists' | 'videos' = 'channels') {
  await new Promise(resolve => setTimeout(resolve, 800));
  const lowerQuery = query.toLowerCase();

  return {
    channels: type === 'channels' ? mockChannels.filter(ch => ch.name?.toLowerCase().includes(lowerQuery)) : [],
    playlists: type === 'playlists' ? mockPlaylists.filter(pl => pl.title?.toLowerCase().includes(lowerQuery)) : [],
    videos: type === 'videos' ? mockVideos.filter(v => v.title?.toLowerCase().includes(lowerQuery)) : []
  };
}

export async function getChannelDetails(channelId: string) {
  await new Promise(resolve => setTimeout(resolve, 500));
  const channel = mockChannels.find(ch => ch.id === channelId);
  if (!channel) throw new Error('Channel not found');

  return {
    channel,
    videos: mockVideos.filter(v => v.parentChannelId === channelId),
    playlists: mockPlaylists.filter(pl => pl.parentChannelId === channelId)
  };
}

export async function toggleIncludeState(itemId: string, itemType: string, newState: string) {
  await new Promise(resolve => setTimeout(resolve, 300));
  console.log(`Mock: Toggling ${itemType} ${itemId} to ${newState}`);
}
