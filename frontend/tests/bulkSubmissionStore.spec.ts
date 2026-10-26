import { setActivePinia, createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'
import { bulkSubmissionService } from '../src/services/bulkSubmissionService'
import { i18n } from '../src/main'
import { liveCategoryIds } from '../src/components/contentSearch/bulk/useCategoryPicker'

vi.mock('../src/components/contentSearch/bulk/useCategoryPicker', () => ({
  liveCategoryIds: vi.fn(),
}))
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

describe('bulkSubmissionStore: coded errors read in the admin language (F20)', () => {
  const okRow = { rowIndex: 0, status: 'OK', originalUrl: 'u', detectedType: 'VIDEO', metadata: {}, categoryIds: ['cat-1'] } as any
  const coded = (status: number, data: Record<string, unknown>) =>
    Object.assign(new Error(`Request failed with status code ${status}`), { response: { status, data } })

  it('translates a 429 RATE_LIMIT instead of showing the server English', async () => {
    mockedSubmit.mockRejectedValue(coded(429, { code: 'RATE_LIMIT', message: 'Daily limit of 50 submissions reached' }))
    const s = useBulkSubmissionStore()
    s.previewRows = [okRow]

    await s.runSubmit()

    expect(s.phase).toBe('PREVIEW')
    expect(s.error).toBe(i18n.global.t('apiErrors.RATE_LIMIT'))
  })

  it('lists each offender of a live-category refusal (D9)', async () => {
    mockedSubmit.mockRejectedValue(coded(403, { code: 'LIVE_CATEGORY_ADMIN_ONLY', message: 'x',
      details: { offenders: [{ ref: '0', code: 'LIVE_CATEGORY_ADMIN_ONLY' }] } }))
    const s = useBulkSubmissionStore()
    s.previewRows = [okRow]

    await s.runSubmit()

    const t = i18n.global.t
    expect(s.error).toBe(`${t('apiErrors.LIVE_CATEGORY_ADMIN_ONLY')}\n#1: ${t('apiErrors.LIVE_CATEGORY_ADMIN_ONLY')}`)
  })

  it('keeps the server message for an uncoded error', async () => {
    mockedSubmit.mockRejectedValue(coded(400, { message: 'rows must not be empty' }))
    const s = useBulkSubmissionStore()
    s.previewRows = [okRow]

    await s.runSubmit()

    expect(s.error).toBe('rows must not be empty')
  })

  it('runSubmit strips LIVE default categories from playlist rows only', async () => {
    mockedSubmit.mockResolvedValue({ totalSubmitted: 2, added: 2, failed: 0, results: [] } as any)
    vi.mocked(liveCategoryIds).mockResolvedValue(new Set(['live-1']))
    const s = useBulkSubmissionStore()
    s.phase = 'PREVIEW'
    s.defaultCategoryIds = ['live-1', 'quran']
    s.previewRows = [
      { rowIndex: 0, status: 'OK', originalUrl: 'c', detectedType: 'CHANNEL', metadata: {} } as any,
      { rowIndex: 1, status: 'OK', originalUrl: 'p', detectedType: 'PLAYLIST', metadata: {} } as any,
    ]
    await s.runSubmit()
    const rows = mockedSubmit.mock.calls[0][0].rows
    expect(rows[0].categoryIds).toEqual(['live-1', 'quran'])
    expect(rows[1].categoryIds).toEqual(['quran'])
  })

  it('runSubmit flags a playlist row left with no category and sends nothing', async () => {
    vi.mocked(liveCategoryIds).mockResolvedValue(new Set(['live-1']))
    const s = useBulkSubmissionStore()
    s.phase = 'PREVIEW'
    s.defaultCategoryIds = ['live-1']
    s.previewRows = [
      { rowIndex: 0, status: 'OK', originalUrl: 'c', detectedType: 'CHANNEL', metadata: {} } as any,
      { rowIndex: 1, status: 'OK', originalUrl: 'p', detectedType: 'PLAYLIST', metadata: {} } as any,
    ]
    await s.runSubmit()
    expect(mockedSubmit).not.toHaveBeenCalled()
    expect(s.phase).toBe('PREVIEW')
    expect(s.rowsNeedingCategory).toEqual([1])
    expect(s.error).toBe(i18n.global.t('contentSearch.bulk.preview.playlistNeedsCategory'))

    s.setRowCategories(1, ['quran'])
    expect(s.rowsNeedingCategory).toEqual([])
    mockedSubmit.mockResolvedValue({ totalSubmitted: 2, added: 2, failed: 0, results: [] } as any)
    await s.runSubmit()
    expect(mockedSubmit.mock.calls[0][0].rows[1].categoryIds).toEqual(['quran'])
  })

  it('a new preview clears the rows flagged by an earlier submit', async () => {
    mockedPreview.mockResolvedValue({ rows: [] } as any)
    const s = useBulkSubmissionStore()
    s.parsedUrls = ['u']
    s.defaultCategoryIds = ['quran']
    s.rowsNeedingCategory = [1]
    await s.runPreview()
    expect(s.rowsNeedingCategory).toEqual([])
  })
})
