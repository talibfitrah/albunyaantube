import { ref } from 'vue';

export interface Toast {
  id: number;
  type: 'success' | 'error' | 'info' | 'warning';
  message: string;
  duration: number;
}

const toasts = ref<Toast[]>([]);
let nextId = 1;

export function useToast() {
  function show(type: Toast['type'], message: string, duration = 5000) {
    const id = nextId++;
    const toast: Toast = { id, type, message, duration };
    toasts.value.push(toast);

    if (duration > 0) {
      setTimeout(() => {
        remove(id);
      }, duration);
    }

    return id;
  }

  function success(message: string, duration?: number) {
    return show('success', message, duration);
  }

  function error(message: string, duration?: number) {
    return show('error', message, duration);
  }

  function info(message: string, duration?: number) {
    return show('info', message, duration);
  }

  function warning(message: string, duration?: number) {
    return show('warning', message, duration);
  }

  function remove(id: number) {
    const index = toasts.value.findIndex(t => t.id === id);
    if (index !== -1) {
      toasts.value.splice(index, 1);
    }
  }

  return {
    toasts,
    show,
    success,
    error,
    info,
    warning,
    remove
  };
}
