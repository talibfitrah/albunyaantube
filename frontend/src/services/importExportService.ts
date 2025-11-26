import api from './api/client'
import type { ValidationRun } from '@/types/validation'

export interface SimpleImportItemResult {
  youtubeId: string
  title: string
  type: 'CHANNEL' | 'PLAYLIST' | 'VIDEO'
  status: 'SUCCESS' | 'SKIPPED' | 'FAILED'
  errorReason?: string
}

export interface SimpleImportResponse {
  success: boolean
  message: string
  counts: {
    channelsImported: number
    channelsSkipped: number
    playlistsImported: number
    playlistsSkipped: number
    videosImported: number
    videosSkipped: number
    totalErrors: number
    totalProcessed: number
  }
  results: SimpleImportItemResult[]
  importedAt: string
}

export interface ExportOptions {
  includeCategories?: boolean
  includeChannels?: boolean
  includePlaylists?: boolean
  includeVideos?: boolean
}

export interface ImportResponse {
  success: boolean
  message: string
  counts: {
    categoriesImported: number
    categoriesSkipped: number
    channelsImported: number
    channelsSkipped: number
    playlistsImported: number
    playlistsSkipped: number
    videosImported: number
    videosSkipped: number
    totalErrors: number
  }
  errors: Array<{
    type: string
    id: string
    error: string
  }>
  importedAt: string
}

/**
 * Response when starting async import
 */
export interface AsyncImportResponse {
  success: boolean
  runId: string
  status: 'RUNNING'
  message: string
}

/**
 * Import status response
 */
export interface ImportStatusResponse {
  success: boolean
  run: ValidationRun
}

/**
 * Import/Export service for bulk content operations.
 * Supports both full format (complete backup) and simple format (quick bulk import).
 */
class ImportExportService {
  private readonly BASE_PATH = '/api/admin/import-export'

  // ============================================================
  // Simple Format (Quick Bulk Import)
  // ============================================================

  /**
   * Export content in simple format: [{channelId: "Title|Cat1,Cat2"}, ...]
   * Only exports APPROVED items.
   *
   * @param options Export options (which content types to include)
   * @returns Blob containing JSON file
   */
  async exportSimple(options: ExportOptions = {}): Promise<Blob> {
    const {
      includeChannels = true,
      includePlaylists = true,
      includeVideos = true
    } = options

    const params = new URLSearchParams({
      includeChannels: String(includeChannels),
      includePlaylists: String(includePlaylists),
      includeVideos: String(includeVideos)
    })

    const response = await api.get(`${this.BASE_PATH}/export/simple?${params}`, {
      responseType: 'blob'
    })

    return response.data
  }

  /**
   * Import content from simple format JSON file.
   * Validates YouTube IDs still exist and skips duplicates.
   *
   * @param file JSON file in simple format
   * @param defaultStatus Default approval status (APPROVED or PENDING)
   * @returns Import results with counts and errors
   */
  async importSimple(
    file: File,
    defaultStatus: 'APPROVED' | 'PENDING' = 'APPROVED'
  ): Promise<SimpleImportResponse> {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('defaultStatus', defaultStatus)

    const response = await api.post<SimpleImportResponse>(
      `${this.BASE_PATH}/import/simple`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      }
    )

    return response.data
  }

  /**
   * Validate simple format import file without actually importing (dry-run).
   *
   * @param file JSON file in simple format
   * @returns Validation results with potential errors
   */
  async validateSimple(file: File): Promise<SimpleImportResponse> {
    const formData = new FormData()
    formData.append('file', file)

    const response = await api.post<SimpleImportResponse>(
      `${this.BASE_PATH}/import/simple/validate`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      }
    )

    return response.data
  }

  // ============================================================
  // Async Import (For Large Datasets)
  // ============================================================

  /**
   * Import content from simple format asynchronously (for large datasets).
   * Returns immediately with a runId for polling progress.
   * Prevents timeout errors for large imports by processing in background.
   *
   * @param file JSON file in simple format
   * @param defaultStatus Default approval status (APPROVED or PENDING)
   * @returns Async import response with runId
   */
  async importSimpleAsync(
    file: File,
    defaultStatus: 'APPROVED' | 'PENDING' = 'APPROVED'
  ): Promise<AsyncImportResponse> {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('defaultStatus', defaultStatus)

    const response = await api.post<AsyncImportResponse>(
      `${this.BASE_PATH}/import/simple/async`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      }
    )

    return response.data
  }

  /**
   * Get status of an async import run.
   * Poll this endpoint to track import progress.
   *
   * @param runId ValidationRun ID from importSimpleAsync
   * @returns Import status with progress counters
   */
  async getImportStatus(runId: string): Promise<ImportStatusResponse> {
    const response = await api.get<ImportStatusResponse>(
      `${this.BASE_PATH}/import/status/${runId}`
    )

    return response.data
  }

  /**
   * Download failed items from an import run as JSON.
   * Only available after import completes.
   *
   * @param runId ValidationRun ID
   * @returns Blob containing failed items JSON
   */
  async downloadFailedItems(runId: string): Promise<Blob> {
    const response = await api.get(`${this.BASE_PATH}/import/${runId}/failed-items`, {
      responseType: 'blob'
    })

    return response.data
  }

  // ============================================================
  // Full Format (Complete Backup/Restore)
  // ============================================================

  /**
   * Export all content in full format (complete backup with all metadata).
   *
   * @param options Export options (which content types to include)
   * @returns Blob containing JSON file
   */
  async exportFull(options: ExportOptions = {}): Promise<Blob> {
    const {
      includeCategories = true,
      includeChannels = true,
      includePlaylists = true,
      includeVideos = true
    } = options

    const params = new URLSearchParams({
      includeCategories: String(includeCategories),
      includeChannels: String(includeChannels),
      includePlaylists: String(includePlaylists),
      includeVideos: String(includeVideos)
    })

    const response = await api.get(`${this.BASE_PATH}/export?${params}`, {
      responseType: 'blob'
    })

    return response.data
  }

  /**
   * Import content from full format JSON file.
   *
   * @param file JSON file in full format
   * @param mergeStrategy Strategy for handling existing items (SKIP, OVERWRITE, MERGE)
   * @returns Import results with counts and errors
   */
  async importFull(
    file: File,
    mergeStrategy: 'SKIP' | 'OVERWRITE' | 'MERGE' = 'SKIP'
  ): Promise<ImportResponse> {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('mergeStrategy', mergeStrategy)

    const response = await api.post<ImportResponse>(
      `${this.BASE_PATH}/import`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      }
    )

    return response.data
  }

  /**
   * Validate full format import file without actually importing (dry-run).
   *
   * @param file JSON file in full format
   * @returns Validation results with potential errors
   */
  async validateFull(file: File): Promise<ImportResponse> {
    const formData = new FormData()
    formData.append('file', file)

    const response = await api.post<ImportResponse>(
      `${this.BASE_PATH}/import/validate`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      }
    )

    return response.data
  }

  // ============================================================
  // Utility Methods
  // ============================================================

  /**
   * Download a blob as a file.
   *
   * @param blob File blob to download
   * @param filename Filename to save as
   */
  downloadBlob(blob: Blob, filename: string): void {
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
  }

  /**
   * Generate example simple format JSON for template download.
   *
   * @returns Blob containing example JSON
   */
  generateSimpleFormatTemplate(): Blob {
    const template = [
      {
        'UCw0OFJrMMH6N5aTyeOTTWZQ': 'مجموعة زاد|Global',
        'UCOll3M-P7oKs5cSrQ9ytt6g': 'قناة زاد العلمية|Global',
        'UCBoe29aQT-zMECFyyyO7H4Q': 'برنامج أكاديمية زاد - Zad academy|Global'
      },
      {
        'PLEaGEZnOHpUP4SKUKrg3Udghc5zJ_tH0g': 'Arabic Alphabet for Children - حروف الهجاء للأطفال|Global',
        'PLEaGEZnOHpUPBcDnCCXkmgsgRDICnhYwT': 'Learn Arabic for Kids -  تعليم اللغة العربية للأطفال|Global'
      },
      {
        'EnfgPg0Ey3I': 'نشيد طلب العلم|Global',
        '7KP-elyP-EE': 'نشيد قناة زاد العلمية|Global'
      }
    ]

    const json = JSON.stringify(template, null, 2)
    return new Blob([json], { type: 'application/json' })
  }
}

export default new ImportExportService()
