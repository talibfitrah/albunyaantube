/**
 * Mock Approvals Service
 * Replace with real backend API calls when BACKEND-REG-01 is complete.
 */

export interface PendingApproval {
  id: string;
  type: 'channel' | 'playlist' | 'video';
  title: string;
  description: string;
  thumbnailUrl: string;
  channelTitle?: string;
  subscriberCount?: number;
  videoCount?: number;
  categories: string[];
  submittedAt: string;
  submittedBy: string;
}

let mockApprovals: PendingApproval[] = [
  {
    id: 'app-1',
    type: 'channel',
    title: 'Sheikh Ahmad Islamic Lectures',
    description: 'Comprehensive Islamic lectures covering Quran, Hadith, and Fiqh',
    thumbnailUrl: 'https://via.placeholder.com/240x135',
    subscriberCount: 125000,
    videoCount: 450,
    categories: ['cat-1', 'cat-5'],
    submittedAt: '2025-10-03T10:30:00Z',
    submittedBy: 'admin@albunyaan.com'
  },
  {
    id: 'app-2',
    type: 'playlist',
    title: 'Ramadan Tafsir Series 2024',
    description: 'Daily Quran tafsir during Ramadan with detailed explanations',
    thumbnailUrl: 'https://via.placeholder.com/240x135',
    channelTitle: 'Islamic Lectures Channel',
    videoCount: 30,
    categories: ['cat-1'],
    submittedAt: '2025-10-04T14:20:00Z',
    submittedBy: 'moderator@albunyaan.com'
  },
  {
    id: 'app-3',
    type: 'video',
    title: 'The Importance of Prayer in Islam',
    description: 'Understanding the five daily prayers and their significance',
    thumbnailUrl: 'https://via.placeholder.com/240x135',
    channelTitle: 'Fiqh Studies',
    categories: ['cat-2'],
    submittedAt: '2025-10-05T09:15:00Z',
    submittedBy: 'admin@albunyaan.com'
  },
  {
    id: 'app-4',
    type: 'channel',
    title: 'Stories from the Seerah',
    description: 'Detailed narrations from the life of Prophet Muhammad (PBUH)',
    thumbnailUrl: 'https://via.placeholder.com/240x135',
    subscriberCount: 89000,
    videoCount: 120,
    categories: ['cat-4'],
    submittedAt: '2025-10-02T16:45:00Z',
    submittedBy: 'moderator@albunyaan.com'
  },
  {
    id: 'app-5',
    type: 'playlist',
    title: 'Aqeedah Fundamentals',
    description: 'Core beliefs and creed in Islam explained',
    thumbnailUrl: 'https://via.placeholder.com/240x135',
    channelTitle: 'Islamic Lectures Channel',
    videoCount: 15,
    categories: ['cat-3'],
    submittedAt: '2025-10-01T11:00:00Z',
    submittedBy: 'admin@albunyaan.com'
  }
];

export async function getPendingApprovals(filters?: {
  type?: 'all' | 'channels' | 'playlists' | 'videos';
  category?: string;
  sort?: 'oldest' | 'newest';
}): Promise<PendingApproval[]> {
  await new Promise(resolve => setTimeout(resolve, 600));

  let filtered = [...mockApprovals];

  if (filters?.type && filters.type !== 'all') {
    const typeMap: Record<string, string> = {
      channels: 'channel',
      playlists: 'playlist',
      videos: 'video'
    };
    filtered = filtered.filter(item => item.type === typeMap[filters.type!]);
  }

  if (filters?.category) {
    filtered = filtered.filter(item => item.categories.includes(filters.category!));
  }

  if (filters?.sort === 'newest') {
    filtered.sort((a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime());
  } else {
    filtered.sort((a, b) => new Date(a.submittedAt).getTime() - new Date(b.submittedAt).getTime());
  }

  return filtered;
}

export async function approveItem(itemId: string): Promise<void> {
  await new Promise(resolve => setTimeout(resolve, 500));

  const index = mockApprovals.findIndex(item => item.id === itemId);
  if (index !== -1) {
    mockApprovals.splice(index, 1);
    console.log(`Mock: Approved item ${itemId}`);
  }
}

export async function rejectItem(itemId: string, reason: string): Promise<void> {
  await new Promise(resolve => setTimeout(resolve, 500));

  const index = mockApprovals.findIndex(item => item.id === itemId);
  if (index !== -1) {
    mockApprovals.splice(index, 1);
    console.log(`Mock: Rejected item ${itemId} with reason: ${reason}`);
  }
}
