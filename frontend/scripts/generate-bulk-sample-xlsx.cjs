// Run once: node scripts/generate-bulk-sample-xlsx.cjs
const xlsx = require('xlsx')
const path = require('path')

const wb = xlsx.utils.book_new()
const rows = [
  ['URL'],
  ['https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw'],
  ['https://www.youtube.com/playlist?list=PLrAXtmRdnEQy6nuLMHjMZOz59Oq8B9nUj'],
  ['https://www.youtube.com/watch?v=dQw4w9WgXcQ'],
  ['https://www.youtube.com/live/jfKfPfyJRdk'],
]
const ws = xlsx.utils.aoa_to_sheet(rows)
xlsx.utils.book_append_sheet(wb, ws, 'urls')

const outPath = path.join(__dirname, '..', 'public', 'samples', 'sample-bulk-urls.xlsx')
xlsx.writeFile(wb, outPath)
console.log('Written:', outPath)
