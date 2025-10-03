/**
 * FIREBASE-MIGRATE-07: Firebase Authentication Store
 *
 * Replaces custom JWT auth with Firebase Authentication.
 * Manages user sign-in, ID tokens, and authentication state.
 */
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { auth } from '@/config/firebase';
import {
  signInWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
  type User
} from 'firebase/auth';

interface LoginPayload {
  email: string;
  password: string;
}

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<User | null>(null);
  const idToken = ref<string | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);

  // Computed properties
  const isAuthenticated = computed(() => currentUser.value !== null);
  const bearerToken = computed(() => (idToken.value ? `Bearer ${idToken.value}` : null));
  const userEmail = computed(() => currentUser.value?.email || '');

  /**
   * Initialize auth state listener
   * This runs automatically and keeps the store synced with Firebase Auth
   */
  function initializeAuthListener() {
    onAuthStateChanged(auth, async (user) => {
      currentUser.value = user;

      if (user) {
        // Get fresh ID token
        try {
          idToken.value = await user.getIdToken();
        } catch (err) {
          console.error('Failed to get ID token', err);
          idToken.value = null;
        }
      } else {
        idToken.value = null;
      }
    });
  }

  /**
   * Login with email and password using Firebase Auth
   */
  async function login(payload: LoginPayload): Promise<boolean> {
    isLoading.value = true;
    error.value = null;

    try {
      const userCredential = await signInWithEmailAndPassword(
        auth,
        payload.email,
        payload.password
      );

      // Get ID token for API requests
      idToken.value = await userCredential.user.getIdToken();
      currentUser.value = userCredential.user;

      return true;
    } catch (err: any) {
      console.error('Login failed', err);

      // Map Firebase error codes to user-friendly messages
      switch (err.code) {
        case 'auth/invalid-email':
          error.value = 'Invalid email address.';
          break;
        case 'auth/user-disabled':
          error.value = 'This account has been disabled.';
          break;
        case 'auth/user-not-found':
        case 'auth/wrong-password':
        case 'auth/invalid-credential':
          error.value = 'Invalid email or password.';
          break;
        case 'auth/too-many-requests':
          error.value = 'Too many failed attempts. Please try again later.';
          break;
        default:
          error.value = 'Unable to sign in. Please try again.';
      }

      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /**
   * Logout from Firebase Auth
   */
  async function logout(): Promise<void> {
    try {
      await signOut(auth);
      currentUser.value = null;
      idToken.value = null;
    } catch (err) {
      console.error('Logout failed', err);
    }
  }

  /**
   * Refresh ID token
   * Firebase Auth handles token refresh automatically, but this can force it
   */
  async function refreshToken(): Promise<boolean> {
    if (!currentUser.value) {
      return false;
    }

    try {
      idToken.value = await currentUser.value.getIdToken(true); // force refresh
      return true;
    } catch (err) {
      console.error('Token refresh failed', err);
      return false;
    }
  }

  /**
   * Get current ID token (refreshes if needed)
   */
  async function getIdToken(): Promise<string | null> {
    if (!currentUser.value) {
      return null;
    }

    try {
      return await currentUser.value.getIdToken();
    } catch (err) {
      console.error('Failed to get ID token', err);
      return null;
    }
  }

  return {
    currentUser,
    idToken,
    isLoading,
    error,
    isAuthenticated,
    bearerToken,
    userEmail,
    initializeAuthListener,
    login,
    logout,
    refreshToken,
    getIdToken
  };
});
