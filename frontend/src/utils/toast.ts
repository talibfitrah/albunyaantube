/**
 * Toast notification utility
 * Simple wrapper for displaying user notifications
 */

export const toast = {
  success: (message: string) => {
    console.log('[SUCCESS]', message);
    // TODO: Integrate with UI toast library (e.g., vue-toastification)
    if (typeof window !== 'undefined') {
      alert(`✓ ${message}`);
    }
  },

  error: (message: string) => {
    console.error('[ERROR]', message);
    // TODO: Integrate with UI toast library
    if (typeof window !== 'undefined') {
      alert(`✗ ${message}`);
    }
  },

  info: (message: string) => {
    console.info('[INFO]', message);
    // TODO: Integrate with UI toast library
    if (typeof window !== 'undefined') {
      alert(`ℹ ${message}`);
    }
  },

  warning: (message: string) => {
    console.warn('[WARNING]', message);
    // TODO: Integrate with UI toast library
    if (typeof window !== 'undefined') {
      alert(`⚠ ${message}`);
    }
  }
};
