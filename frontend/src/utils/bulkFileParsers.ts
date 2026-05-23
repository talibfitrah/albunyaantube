import Papa from 'papaparse'
import { z } from 'zod'

const MAX_URLS = 25
const MAX_FILE_BYTES = 1_000_000

function ensureFileSize(file: File) {
  if (file.size > MAX_FILE_BYTES) {
    throw new Error(`File exceeds 1 MB limit (${file.size} bytes)`)
  }
}

/** Read a File as text, compatible with jsdom (FileReader) and real browsers (file.text()). */
function readFileAsText(file: File): Promise<string> {
  // file.text() is not available in jsdom; use FileReader which jsdom does support
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

/** Read a File as ArrayBuffer, compatible with jsdom. */
function readFileAsArrayBuffer(file: File): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(file)
  })
}

export async function parseCsv(file: File): Promise<string[]> {
  ensureFileSize(file)
  const text = await readFileAsText(file)
  return new Promise<string[]>((resolve, reject) => {
    Papa.parse<string[]>(text, {
      skipEmptyLines: true,
      // Pre-parse cap. Without `preview`, PapaParse materialises every
      // row before our post-parse `urls.length > MAX_URLS` check — a
      // 999 KB CSV of millions of single-byte rows passes the file-size
      // gate and pegs the main thread. preview=MAX_URLS+2 (header +
      // MAX_URLS data + 1 over-cap detection row) bounds work.
      preview: MAX_URLS + 2,
      complete: (result) => {
        const rows = result.data
        if (rows.length === 0) {
          reject(new Error('Empty CSV'))
          return
        }
        const header = rows[0]
        if (header.length !== 1) {
          reject(new Error('CSV must have a single column named "URL"'))
          return
        }
        // Strip BOM that Excel adds to UTF-8 CSV exports — without this,
        // the documented Excel-author roundtrip fails the header check.
        const headerName = header[0].replace(/^﻿/, '').trim().toLowerCase()
        if (headerName !== 'url') {
          reject(new Error('CSV must have a URL header in row 1'))
          return
        }
        const urls = rows.slice(1).map((r) => r[0]?.trim()).filter((u): u is string => !!u)
        if (urls.length > MAX_URLS) {
          reject(new Error(`Too many rows: ${urls.length} > ${MAX_URLS}`))
          return
        }
        resolve(urls)
      },
      error: (err: Error) => reject(err),
    })
  })
}

const JsonShape = z.object({
  urls: z.array(z.string().min(1)).min(1).max(MAX_URLS),
})

export async function parseJson(file: File): Promise<string[]> {
  ensureFileSize(file)
  const text = await readFileAsText(file)
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch (e) {
    throw new Error(`Invalid JSON: ${(e as Error).message}`)
  }
  const result = JsonShape.safeParse(raw)
  if (!result.success) {
    const issue = result.error.issues[0]
    throw new Error(
      `JSON must match { urls: string[] } (≤${MAX_URLS}). ${issue.path.join('.')}: ${issue.message}`,
    )
  }
  return result.data.urls
}

/**
 * Excel parser is lazy-loaded so SheetJS (~600 KB) doesn't bloat the main bundle.
 * Only loads when the admin actually uses Excel upload.
 */
export async function parseExcel(file: File): Promise<string[]> {
  ensureFileSize(file)
  const { read, utils } = await import('@e965/xlsx')
  const buf = await readFileAsArrayBuffer(file)
  // Pre-parse cap on XLSX rows. A 1 MB malicious xlsx (XML-bomb /
  // pathological row count) can expand to hundreds of MB inside SheetJS
  // if every row is materialised before the post-parse `urls.length >
  // MAX_URLS` check. `sheetRows: MAX_URLS + 2` (header + MAX_URLS + 1
  // for over-cap detection) bounds SheetJS work.
  const wb = read(buf, { type: 'array', sheetRows: MAX_URLS + 2 })
  if (wb.SheetNames.length === 0) throw new Error('Empty workbook')
  const sheet = wb.Sheets[wb.SheetNames[0]]
  const rows = utils.sheet_to_json<string[]>(sheet, { header: 1, blankrows: false })
  if (rows.length === 0) throw new Error('Empty sheet')
  const header = rows[0]
  if (!Array.isArray(header) || header.length !== 1) {
    throw new Error('Excel sheet must have a single column named "URL"')
  }
  if (String(header[0]).trim().toLowerCase() !== 'url') {
    throw new Error('Excel sheet must have a URL header in row 1')
  }
  const urls = rows
    .slice(1)
    .map((r) => String(r[0] ?? '').trim())
    .filter((u) => u.length > 0)
  if (urls.length > MAX_URLS) {
    throw new Error(`Too many rows: ${urls.length} > ${MAX_URLS}`)
  }
  return urls
}

export function parsePastedUrls(raw: string): string[] {
  return raw
    .split(/[,\n\r\s]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}
