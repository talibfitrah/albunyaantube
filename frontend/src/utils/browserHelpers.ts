/**
 * Browser Helper Utilities
 * Pure functions for browser-specific operations
 */

/**
 * Download a blob as a file in the browser.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
}

/**
 * Generate a JSON blob from data.
 */
export function createJsonBlob(data: unknown): Blob {
  const json = JSON.stringify(data, null, 2);
  return new Blob([json], { type: 'application/json' });
}
