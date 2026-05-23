import { setActivePinia, createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'
import { bulkSubmissionService } from '../src/services/bulkSubmissionService'

vi.mock('../src/services/bulkSubmissionService', () => ({
  bulkSubmissionService: { previewBulk: vi.fn(), submitBulk: vi.fn() },
}))

const mockedPreview = vi.mocked(bulkSubmissionService.previewBulk)
const mockedSubmit  = vi.mocked(bulkSubmissionService.submitBulk)

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})
afterEach(() => vi.restoreAllMocks())

describe('bulkSubmissionStore', () => {
  it('initial state is INPUT phase, empty', () => {
    const s = useBulkSubmissionStore()
    expect(s.phase).toBe('INPUT')
    expect(s.parsedUrls).toEqual([])
    expect(s.defaultCategoryIds).toEqual([])
    expect(s.previewRows).toEqual([])
  })

  it('runPreview transitions LOADING → PREVIEW and stores rows', async () => {
    mockedPreview.mockResolvedValue({
      rows: [{ rowIndex: 0, originalUrl: 'u', detectedType: 'VIDEO',
              videoType: 'STANDARD', metadata: { youtubeId: 'x', title: 't' } as any,
              status: 'OK', duplicateOf: null, duplicateStatus: null, error: null } as any],
    })
    const s = useBulkSubmissionStore()
    s.parsedUrls = ['u']
    s.defaultCategoryIds = ['cat-1']

    const p = s.runPreview()
    expect(s.phase).toBe('LOADING')
    await p
    expect(s.phase).toBe('PREVIEW')
    expect(s.previewRows.length).toBe(1)
    expect(mockedPreview).toHaveBeenCalledWith({ urls: ['u'] })
  })

  it('setRowCategories overrides the resolved categoryIds for a single row', () => {
    const s = useBulkSubmissionStore()
    s.defaultCategoryIds = ['cat-1']
    s.previewRows = [
      { rowIndex: 0, originalUrl: 'u', categoryIds: ['cat-1'] } as any,
    ]
    s.setRowCategories(0, ['cat-2', 'cat-3'])
    expect(s.previewRows[0].categoryIds).toEqual(['cat-2', 'cat-3'])
  })

  it('removeRow drops the row at the given index', () => {
    const s = useBulkSubmissionStore()
    s.previewRows = [{ rowIndex: 0 } as any, { rowIndex: 1 } as any, { rowIndex: 2 } as any]
    s.removeRow(1)
    expect(s.previewRows.map((r) => r.rowIndex)).toEqual([0, 2])
  })

  it('runSubmit filters to OK + DUPLICATE_REJECTED, transitions to RESULT', async () => {
    mockedSubmit.mockResolvedValue({ totalSubmitted: 1, added: 1, failed: 0, results: [{ rowIndex: 0, originalUrl: 'u', registryId: 'r', status: 'ADDED', errorCode: null } as any] })
    const s = useBulkSubmissionStore()
    s.phase = 'PREVIEW'
    s.previewRows = [
      { rowIndex: 0, status: 'OK', originalUrl: 'u', detectedType: 'VIDEO', videoType: 'STANDARD', metadata: {}, categoryIds: ['cat-1'] } as any,
      { rowIndex: 1, status: 'ERROR' } as any,
      { rowIndex: 2, status: 'DUPLICATE' } as any,
    ]

    await s.runSubmit()
    expect(s.phase).toBe('RESULT')
    expect(mockedSubmit.mock.calls[0][0].rows).toHaveLength(1)
    expect(s.submitResult?.added).toBe(1)
  })

  it('reset returns to INPUT phase and clears state', () => {
    const s = useBulkSubmissionStore()
    s.phase = 'RESULT'
    s.parsedUrls = ['u']
    s.previewRows = [{} as any]
    s.submitResult = { added: 1 } as any
    s.reset()
    expect(s.phase).toBe('INPUT')
    expect(s.parsedUrls).toEqual([])
    expect(s.previewRows).toEqual([])
    expect(s.submitResult).toBeNull()
  })
})
