import { describe, expect, it } from 'vitest'
import { parseCsv, parseJson, parsePastedUrls } from '../src/utils/bulkFileParsers'

describe('parseCsv', () => {
  it('extracts URLs from a single-column CSV with URL header', async () => {
    const csv = 'URL\nhttps://www.youtube.com/watch?v=AAAAAAAAAAA\nhttps://www.youtube.com/watch?v=BBBBBBBBBBB\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    const result = await parseCsv(file)
    expect(result).toEqual([
      'https://www.youtube.com/watch?v=AAAAAAAAAAA',
      'https://www.youtube.com/watch?v=BBBBBBBBBBB',
    ])
  })

  it('is case-insensitive on the URL header', async () => {
    const csv = 'url\nhttps://www.youtube.com/watch?v=AAAAAAAAAAA\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    const result = await parseCsv(file)
    expect(result).toEqual(['https://www.youtube.com/watch?v=AAAAAAAAAAA'])
  })

  it('rejects when header is missing', async () => {
    const csv = 'https://www.youtube.com/watch?v=AAAAAAAAAAA\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    await expect(parseCsv(file)).rejects.toThrow(/URL header/i)
  })

  it('rejects when more than one column present', async () => {
    const csv = 'URL,name\nhttps://example.com,foo\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    await expect(parseCsv(file)).rejects.toThrow(/single column/i)
  })

  it('rejects more than 25 rows', async () => {
    const rows = Array.from({ length: 26 }, () => 'https://www.youtube.com/watch?v=AAAAAAAAAAA')
    const csv = 'URL\n' + rows.join('\n') + '\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    await expect(parseCsv(file)).rejects.toThrow(/25/)
  })
})

describe('parseJson', () => {
  it('extracts urls array', async () => {
    const obj = { urls: ['https://www.youtube.com/watch?v=AAAAAAAAAAA'] }
    const file = new File([JSON.stringify(obj)], 'urls.json', { type: 'application/json' })
    const result = await parseJson(file)
    expect(result).toEqual(['https://www.youtube.com/watch?v=AAAAAAAAAAA'])
  })

  it('rejects malformed JSON', async () => {
    const file = new File(['{not json'], 'urls.json', { type: 'application/json' })
    await expect(parseJson(file)).rejects.toThrow(/JSON/)
  })

  it('rejects wrong shape', async () => {
    const obj = { wrong: 'shape' }
    const file = new File([JSON.stringify(obj)], 'urls.json', { type: 'application/json' })
    await expect(parseJson(file)).rejects.toThrow(/urls/)
  })

  it('rejects more than 25 entries', async () => {
    const urls = Array.from({ length: 26 }, () => 'https://www.youtube.com/watch?v=AAAAAAAAAAA')
    const file = new File([JSON.stringify({ urls })], 'urls.json', { type: 'application/json' })
    await expect(parseJson(file)).rejects.toThrow(/25/)
  })
})

describe('parsePastedUrls', () => {
  it('splits on commas, newlines, and whitespace', () => {
    const raw = 'https://a.com,https://b.com\nhttps://c.com  https://d.com'
    expect(parsePastedUrls(raw)).toEqual(['https://a.com', 'https://b.com', 'https://c.com', 'https://d.com'])
  })

  it('returns empty array for blank input', () => {
    expect(parsePastedUrls('')).toEqual([])
    expect(parsePastedUrls('   \n  ')).toEqual([])
  })
})
