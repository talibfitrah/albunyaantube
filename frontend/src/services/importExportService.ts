import api from './api/client';
import { downloadBlob, createJsonBlob } from '@/utils/browserHelpers';

export interface SimpleImportItemResult {
  youtubeId: string;
  title: string;
  type: 'CHANNEL' | 'PLAYLIST' | 'VIDEO';
  status: 'SUCCESS' | 'SKIPPED' | 'FAILED';
  errorReason?: string;
}

export interface SimpleImportResponse {
  success: boolean;
  message: string;
  counts: {
    channelsImported: number;
    channelsSkipped: number;
    playlistsImported: number;
    playlistsSkipped: number;
    videosImported: number;
    videosSkipped: number;
    totalErrors: number;
    totalProcessed: number;
  };
  results: SimpleImportItemResult[];
  importedAt: string;
}

export interface ExportOptions {
  includeCategories?: boolean;
  includeChannels?: boolean;
  includePlaylists?: boolean;
  includeVideos?: boolean;
}

export interface ImportResponse {
  success: boolean;
  message: string;
  counts: {
    categoriesImported: number;
    categoriesSkipped: number;
    channelsImported: number;
    channelsSkipped: number;
    playlistsImported: number;
    playlistsSkipped: number;
    videosImported: number;
    videosSkipped: number;
    totalErrors: number;
  };
  errors: Array<{
    type: string;
    id: string;
    error: string;
  }>;
  importedAt: string;
}

const BASE_PATH = '/api/admin/import-export';

// Simple format export
export async function exportSimple(options: ExportOptions = {}): Promise<Blob> {
  const { includeChannels = true, includePlaylists = true, includeVideos = true } = options;
  const params = new URLSearchParams({
    includeChannels: String(includeChannels),
    includePlaylists: String(includePlaylists),
    includeVideos: String(includeVideos)
  });
  const response = await api.get(`${BASE_PATH}/export/simple?${params}`, { responseType: 'blob' });
  return response.data;
}

// Simple format import
export async function importSimple(
  file: File,
  defaultStatus: 'APPROVED' | 'PENDING' = 'APPROVED'
): Promise<SimpleImportResponse> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('defaultStatus', defaultStatus);
  const response = await api.post<SimpleImportResponse>(`${BASE_PATH}/import/simple`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });
  return response.data;
}

// Validate simple format (dry-run)
export async function validateSimple(file: File): Promise<SimpleImportResponse> {
  const formData = new FormData();
  formData.append('file', file);
  const response = await api.post<SimpleImportResponse>(`${BASE_PATH}/import/simple/validate`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });
  return response.data;
}

// Full format export
export async function exportFull(options: ExportOptions = {}): Promise<Blob> {
  const { includeCategories = true, includeChannels = true, includePlaylists = true, includeVideos = true } = options;
  const params = new URLSearchParams({
    includeCategories: String(includeCategories),
    includeChannels: String(includeChannels),
    includePlaylists: String(includePlaylists),
    includeVideos: String(includeVideos)
  });
  const response = await api.get(`${BASE_PATH}/export?${params}`, { responseType: 'blob' });
  return response.data;
}

// Full format import
export async function importFull(
  file: File,
  mergeStrategy: 'SKIP' | 'OVERWRITE' | 'MERGE' = 'SKIP'
): Promise<ImportResponse> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('mergeStrategy', mergeStrategy);
  const response = await api.post<ImportResponse>(`${BASE_PATH}/import`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });
  return response.data;
}

// Validate full format (dry-run)
export async function validateFull(file: File): Promise<ImportResponse> {
  const formData = new FormData();
  formData.append('file', file);
  const response = await api.post<ImportResponse>(`${BASE_PATH}/import/validate`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });
  return response.data;
}

// Download a blob as a file
export { downloadBlob };

// Generate example template
export function generateSimpleFormatTemplate(): Blob {
  const template = [
    {
      'UCw0OFJrMMH6N5aTyeOTTWZQ': 'مجموعة زاد|Global',
      'UCOll3M-P7oKs5cSrQ9ytt6g': 'قناة زاد العلمية|Global'
    },
    {
      'PLEaGEZnOHpUP4SKUKrg3Udghc5zJ_tH0g': 'Arabic Alphabet for Children|Global'
    },
    {
      'EnfgPg0Ey3I': 'نشيد طلب العلم|Global'
    }
  ];
  return createJsonBlob(template);
}
