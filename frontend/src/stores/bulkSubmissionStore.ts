import { defineStore } from 'pinia'
import type { components } from '../generated/api/schema'
import { bulkSubmissionService } from '../services/bulkSubmissionService'

type PreviewRow      = components['schemas']['PreviewRow']
type BulkSubmitResponse = components['schemas']['BulkSubmitResponse']
type SubmitRow       = components['schemas']['SubmitRow']

type Phase = 'INPUT' | 'LOADING' | 'PREVIEW' | 'RESULT'

/** PreviewRow extended with a per-row category override. */
interface PreviewRowDraft extends PreviewRow {
  categoryIds?: string[]
}

interface State {
  phase: Phase
  pastedUrls: string
  parsedUrls: string[]
  defaultCategoryIds: string[]
  previewRows: PreviewRowDraft[]
  submitResult: BulkSubmitResponse | null
  error: string | null
}

/**
 * Pinia state machine for the bulk URL submission flow.
 *
 * Phase transitions:
 *   INPUT → LOADING → PREVIEW   (runPreview success)
 *   INPUT → LOADING → INPUT     (runPreview error)
 *   PREVIEW → LOADING → RESULT  (runSubmit success)
 *   PREVIEW → LOADING → PREVIEW (runSubmit error)
 *   any → INPUT                 (reset)
 */
export const useBulkSubmissionStore = defineStore('bulkSubmission', {
  state: (): State => ({
    phase: 'INPUT',
    pastedUrls: '',
    parsedUrls: [],
    defaultCategoryIds: [],
    previewRows: [],
    submitResult: null,
    error: null,
  }),

  actions: {
    async runPreview() {
      if (this.parsedUrls.length === 0 || this.defaultCategoryIds.length === 0) {
        this.error = 'URLs and at least one default category required'
        return
      }
      this.phase = 'LOADING'
      this.error = null
      try {
        const resp = await bulkSubmissionService.previewBulk({
          urls: this.parsedUrls,
        })
        this.previewRows = resp.rows ?? []
        this.phase = 'PREVIEW'
      } catch (e) {
        this.error = (e as Error).message
        this.phase = 'INPUT'
      }
    },

    setRowCategories(rowIndex: number, categoryIds: string[]) {
      const idx = this.previewRows.findIndex((r) => r.rowIndex === rowIndex)
      if (idx < 0) return
      this.previewRows[idx] = { ...this.previewRows[idx], categoryIds }
    },

    removeRow(rowIndex: number) {
      this.previewRows = this.previewRows.filter((r) => r.rowIndex !== rowIndex)
    },

    async runSubmit() {
      // DUPLICATE_REJECTED rows are dropped from submittable.
      // Reusing a previously-rejected youtubeId via the bulk path
      // would create a second Firestore doc (the writer always inserts fresh,
      // never updates the existing rejected doc), and the legacy rejection
      // metadata + audit trail would be silently shadowed. Resubmission of
      // rejected items must go through the approval queue UI which updates
      // the existing doc back to PENDING with proper provenance.
      const submittable = this.previewRows.filter((r) => r.status === 'OK')
      if (submittable.length === 0) {
        this.error = 'No valid rows to submit'
        return
      }
      this.phase = 'LOADING'
      const rows: SubmitRow[] = submittable.map((r) => ({
        rowIndex: r.rowIndex!,
        originalUrl: r.originalUrl!,
        detectedType: r.detectedType!,
        videoType: r.videoType,
        metadata: r.metadata!,
        categoryIds: r.categoryIds ?? this.defaultCategoryIds,
      }))
      try {
        const resp = await bulkSubmissionService.submitBulk({ rows })
        this.submitResult = resp
        this.phase = 'RESULT'
      } catch (e) {
        this.error = (e as Error).message
        this.phase = 'PREVIEW'
      }
    },

    reset() {
      this.phase = 'INPUT'
      this.pastedUrls = ''
      this.parsedUrls = []
      this.defaultCategoryIds = []
      this.previewRows = []
      this.submitResult = null
      this.error = null
    },
  },
})
