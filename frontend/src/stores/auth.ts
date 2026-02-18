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
  updatePassword,
  updateProfile,
  reauthenticateWithCredential,
  EmailAuthProvider,
  type User
} from 'firebase/auth';

export type UserRole = 'ADMIN' | 'MODERATOR' | null;

interface LoginPayload {
  email: string;
  password: string;
}

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<User | null>(null);
  const idToken = ref<string | null>(null);
  const userRole = ref<UserRole>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const authInitialized = ref(false);

  // Computed properties
  const isAuthenticated = computed(() => currentUser.value !== null);
  const isAdmin = computed(() => userRole.value === 'ADMIN');
  const isModerator = computed(() => userRole.value === 'MODERATOR');
  const bearerToken = computed(() => (idToken.value ? `Bearer ${idToken.value}` : null));
  const userEmail = computed(() => currentUser.value?.email || '');

  /**
   * Extract role from Firebase ID token claims.
   * Uses getIdTokenResult() which exposes custom claims set by the backend.
   */
  async function extractRole(user: User): Promise<UserRole> {
    try {
      const tokenResult = await user.getIdTokenResult();
      const role = tokenResult.claims.role as string | undefined;
      if (role) {
        const normalized = role.toUpperCase();
        if (normalized === 'ADMIN' || normalized === 'MODERATOR') {
          return normalized;
        }
      }
      return null;
    } catch (err) {
      console.error('Failed to extract role from token', err);
      return null;
    }
  }

  /**
   * Initialize auth state listener
   * This runs automatically and keeps the store synced with Firebase Auth
   */
  function initializeAuthListener(): Promise<void> {
    return new Promise((resolve) => {
      onAuthStateChanged(auth, async (user) => {
        currentUser.value = user;

        if (user) {
          // Get fresh ID token and extract role from claims
          try {
            idToken.value = await user.getIdToken();
            userRole.value = await extractRole(user);
          } catch (err) {
            console.error('Failed to get ID token', err);
            idToken.value = null;
            userRole.value = null;
          }
        } else {
          idToken.value = null;
          userRole.value = null;
        }

        // Mark as initialized on first auth state change
        if (!authInitialized.value) {
          authInitialized.value = true;
          resolve();
        }
      });
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

      // Get ID token for API requests and extract role from claims
      idToken.value = await userCredential.user.getIdToken();
      userRole.value = await extractRole(userCredential.user);
      currentUser.value = userCredential.user;

      return true;
    } catch (err: any) {
      // Log only the error code for debugging, not the full error object
      console.warn('Login failed:', err.code || 'Unknown error');

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
      userRole.value = null;
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
      userRole.value = await extractRole(currentUser.value); // re-extract role from refreshed token
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

  /**
   * Change password using Firebase Auth client-side re-authentication
   */
  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    if (!currentUser.value || !currentUser.value.email) {
      throw new Error('Not authenticated');
    }

    isLoading.value = true;
    error.value = null;

    try {
      const credential = EmailAuthProvider.credential(
        currentUser.value.email,
        currentPassword
      );
      await reauthenticateWithCredential(currentUser.value, credential);
      await updatePassword(currentUser.value, newPassword);
    } catch (err: any) {
      switch (err.code) {
        case 'auth/wrong-password':
        case 'auth/invalid-credential':
          error.value = 'Current password is incorrect.';
          break;
        case 'auth/weak-password':
          error.value = 'New password is too weak.';
          break;
        case 'auth/requires-recent-login':
          error.value = 'Session expired. Please sign in again.';
          break;
        default:
          error.value = err.message || 'Failed to change password';
      }
      throw err;
    } finally {
      isLoading.value = false;
    }
  }

  async function updateDisplayName(newDisplayName: string): Promise<void> {
    if (!currentUser.value) {
      throw new Error('Not authenticated');
    }
    await updateProfile(currentUser.value, { displayName: newDisplayName });
  }

  return {
    currentUser,
    idToken,
    userRole,
    isLoading,
    error,
    authInitialized,
    isAuthenticated,
    isAdmin,
    isModerator,
    bearerToken,
    userEmail,
    initializeAuthListener,
    login,
    logout,
    refreshToken,
    getIdToken,
    changePassword,
    updateDisplayName
  };
});
