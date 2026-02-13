/**
 * API Client with Axios
 * Provides centralized HTTP client with auth and error handling
 */

import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '@/stores/auth';
import { toast } from '@/utils/toast';

// In development, use relative URL to go through Vite proxy (avoids CORS)
// In production, use the configured API base URL or fall back to relative URLs
const API_BASE_URL = import.meta.env.DEV
  ? '' // Empty = relative, goes through Vite proxy
  : (import.meta.env.VITE_API_BASE_URL || ''); // Fall back to relative if not set

// Create Axios instance
export const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'application/json'
  }
});

// Request interceptor - Add auth token
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const authStore = useAuthStore();
    if (authStore.idToken) {
      config.headers.Authorization = `Bearer ${authStore.idToken}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor - Handle errors
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const authStore = useAuthStore();

    // Handle 401 - Token expired, try refresh
    if (error.response?.status === 401 && !error.config?.headers['X-Retry']) {
      const refreshed = await authStore.refreshToken();
      if (refreshed && error.config) {
        error.config.headers['X-Retry'] = 'true';
        return apiClient.request(error.config);
      } else {
        // Refresh failed, redirect to login
        authStore.logout();
        window.location.href = '/login';
      }
    }

    // Handle 403 - Forbidden
    if (error.response?.status === 403) {
      toast.error('You do not have permission to perform this action');
    }

    // Handle 404 - Not Found
    if (error.response?.status === 404) {
      toast.error('Resource not found');
    }

    // Handle 500 - Server Error
    if (error.response?.status === 500) {
      toast.error('Server error. Please try again later.');
    }

    return Promise.reject(error);
  }
);

export default apiClient;
