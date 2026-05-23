/**
 * FIREBASE-MIGRATE-07: Firebase Authentication Store
 *
 * Replaces custom JWT auth with Firebase Authentication.
 * Manages user sign-in, ID tokens, and authentication state.
 */
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { auth, googleProvider } from '@/config/firebase';
import {
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  onAuthStateChanged,
  updatePassword,
  updateProfile,
  reauthenticateWithCredential,
  fetchSignInMethodsForEmail,
  linkWithCredential,
  EmailAuthProvider,
  GoogleAuthProvider,
  type AuthCredential,
  type User
} from 'firebase/auth';

// Auth error strings are kept as hardcoded English to match the existing
// convention in login() (which has 6 English strings). Importing i18n via
// `@/main` here creates a circular dependency that Vite resolves by
// bundling all of main.ts's transitive deps (pinia, router, vue-i18n,
// bulk store) into the LoginView chunk — a 20x bundle-size regression
// on the most performance-critical route. If we ever i18n auth errors,
// the right path is to refactor `error` to hold an i18n key (not a
// string) and translate in the component templates.

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

  // Google account-linking state. When a user tries Google sign-in for an
  // email that already exists as a password account (admin-provisioned),
  // Firebase throws account-exists-with-different-credential. We capture
  // the pending OAuthCredential + the email and surface a one-time
  // password prompt; on submit we sign in with password then call
  // linkWithCredential to attach Google for future seamless sign-in.
  const pendingGoogleCredential = ref<AuthCredential | null>(null);
  const linkingEmail = ref<string | null>(null);

  // Computed properties.
  // isAuthenticated requires BOTH a current user AND a non-null role so
  // route guards never see a half-authenticated state during the async
  // window between Firebase setting auth.currentUser and our extractRole()
  // resolving the custom claim. Without this, a Google sign-in for an
  // unprovisioned email would briefly pass meta.requiresAuth-only routes
  // before loginWithGoogle's signOut completed.
  const isAuthenticated = computed(() => currentUser.value !== null && userRole.value !== null);
  const isAdmin = computed(() => userRole.value === 'ADMIN');
  const linkingRequired = computed(
    () => pendingGoogleCredential.value !== null && linkingEmail.value !== null
  );
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
      const role = tokenResult.claims.role;
      if (role && typeof role === 'string') {
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
        if (!user) {
          currentUser.value = null;
          idToken.value = null;
          userRole.value = null;
        } else {
          // Capture the uid at callback entry. If another auth-state change
          // fires while we're awaiting (logout, account-switch, token
          // refresh on a different account), auth.currentUser will no
          // longer match — abandon this callback's writes to avoid
          // restoring stale state. Without this guard a rapid
          // logout-then-login could let the slow logout callback's writes
          // overwrite the newer login's state (stage-6 codex finding).
          const callbackUid = user.uid;
          try {
            const newToken = await user.getIdToken();
            const newRole = await extractRole(user);

            if (auth.currentUser?.uid !== callbackUid) {
              // A newer auth event fired during our awaits. Drop this
              // callback's writes entirely — the newer callback owns the
              // commit.
              return;
            }

            if (!newRole) {
              // Authenticated but not provisioned as admin/moderator.
              // Dashboard is admin/moderator-only — sign out so we don't
              // leave a half-authenticated session that the route guard
              // would bounce. The subsequent !user invocation of this
              // listener will null our refs.
              await signOut(auth);
              return;
            }

            idToken.value = newToken;
            userRole.value = newRole;
            currentUser.value = user;
          } catch (err) {
            console.error('Failed to get ID token', err);
            // Only null state if we're still the active callback —
            // otherwise the newer callback's commits would be clobbered.
            if (auth.currentUser?.uid === callbackUid || !auth.currentUser) {
              currentUser.value = null;
              idToken.value = null;
              userRole.value = null;
            }
          }
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

      // Single-writer pattern: extract token+role into locals BEFORE
      // committing currentUser. See isAuthenticated comment above.
      const newToken = await userCredential.user.getIdToken();
      const newRole = await extractRole(userCredential.user);

      if (!newRole) {
        // Authenticated successfully against Firebase but the account
        // isn't provisioned as admin/moderator (USER role or null claim).
        // Pre-fix the store still committed idToken+currentUser and
        // returned true — the route guard then bounced the user back to
        // login, creating a confusing redirect loop while leaving a
        // bearer token in the store for an account with no access.
        // Symmetric with loginWithGoogle's no-role path.
        await signOut(auth);
        error.value = 'This account is not provisioned for the admin dashboard. Ask an administrator for access.';
        return false;
      }

      idToken.value = newToken;
      userRole.value = newRole;
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
   * Login with Google via popup. The dashboard is admin/moderator-only:
   * a Google account with no role custom claim is rejected and signed out.
   * Provisioning happens via the admin user-mgmt flow which creates the
   * Firebase user and sets the role claim — when Google sign-in lands on
   * the same email, Firebase links the provider (one-account-per-email) and
   * the claim is already present on the resulting token.
   */
  async function loginWithGoogle(): Promise<boolean> {
    isLoading.value = true;
    error.value = null;
    // Clear any stale linking state from a prior attempt — a fresh popup
    // means the user is starting over.
    cancelGoogleLink();

    try {
      const userCredential = await signInWithPopup(auth, googleProvider);

      // Extract role BEFORE committing any local state. Pre-fix the manual
      // mutations (idToken first, then role check, then conditional cleanup)
      // raced onAuthStateChanged's own writes — an unprovisioned account
      // could leave isAuthenticated=true between the listener commit and
      // our explicit signOut. Now: extract locally, decide, then either
      // signOut (listener clears state) or commit (single atomic-feeling
      // write with userRole already set so isAuthenticated transitions
      // false→true exactly once).
      const newToken = await userCredential.user.getIdToken();
      const newRole = await extractRole(userCredential.user);

      if (!newRole) {
        // Not provisioned as admin/moderator. Sign out — listener clears
        // our refs. Do NOT mutate local state here ourselves; doing so
        // would race the listener (which may have already committed the
        // signed-in user) and surface a half-cleared store to consumers.
        await signOut(auth);
        error.value = 'This Google account is not provisioned. Ask an administrator to add it.';
        return false;
      }

      idToken.value = newToken;
      userRole.value = newRole;
      currentUser.value = userCredential.user;
      return true;
    } catch (err: any) {
      console.warn('Google login failed:', err.code || 'Unknown error');
      switch (err.code) {
        case 'auth/popup-closed-by-user':
        case 'auth/cancelled-popup-request':
          // User dismissed the popup — clear silently, no error.
          error.value = null;
          break;
        case 'auth/popup-blocked':
          error.value = 'Pop-up was blocked. Allow pop-ups for this site and try again.';
          break;
        case 'auth/account-exists-with-different-credential': {
          // The email is already registered with a password provider
          // (admin-provisioned via the user-mgmt panel). Capture the
          // pending Google credential and the email; UI will collect the
          // password and call completeGoogleLink, which signs in with
          // password then linkWithCredential — after that Google works
          // seamlessly for this account forever.
          const pendingEmail: string | undefined = err.customData?.email;
          const pendingCred = GoogleAuthProvider.credentialFromError(err);
          if (pendingEmail && pendingCred) {
            try {
              const methods = await fetchSignInMethodsForEmail(auth, pendingEmail);
              if (methods.includes('password')) {
                pendingGoogleCredential.value = pendingCred;
                linkingEmail.value = pendingEmail;
                // Clear error so the linking UI shows without an alarm
                // banner; the linking form has its own explainer copy.
                error.value = null;
                return false;
              }
            } catch (lookupErr) {
              console.warn('fetchSignInMethodsForEmail failed', lookupErr);
            }
          }
          error.value = 'An account with this email already exists with a different sign-in method.';
          break;
        }
        case 'auth/network-request-failed':
          error.value = 'Network error. Check your connection and try again.';
          break;
        default:
          error.value = 'Unable to sign in with Google. Please try again.';
      }
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /**
   * Complete the Google account-linking flow started by loginWithGoogle
   * hitting auth/account-exists-with-different-credential. Signs in with
   * password then attaches the pending Google credential so future Google
   * sign-ins succeed directly.
   */
  async function completeGoogleLink(password: string): Promise<boolean> {
    if (!pendingGoogleCredential.value || !linkingEmail.value) {
      error.value = 'No Google sign-in pending.';
      return false;
    }
    const credToLink = pendingGoogleCredential.value;
    const emailToLink = linkingEmail.value;

    isLoading.value = true;
    error.value = null;

    try {
      const userCredential = await signInWithEmailAndPassword(
        auth,
        emailToLink,
        password
      );

      // Link the Google credential to the now-signed-in account. After
      // this, future signInWithPopup(googleProvider) for this email goes
      // straight through (no more account-exists error).
      await linkWithCredential(userCredential.user, credToLink);

      // Same single-writer commit pattern as login() — extract first,
      // commit last, reject if no role.
      const newToken = await userCredential.user.getIdToken();
      const newRole = await extractRole(userCredential.user);

      if (!newRole) {
        await signOut(auth);
        error.value = 'This account is not provisioned for the admin dashboard. Ask an administrator for access.';
        cancelGoogleLink();
        return false;
      }

      idToken.value = newToken;
      userRole.value = newRole;
      currentUser.value = userCredential.user;
      cancelGoogleLink();
      return true;
    } catch (err: any) {
      console.warn('Google link failed:', err.code || 'Unknown error');
      switch (err.code) {
        case 'auth/wrong-password':
        case 'auth/invalid-credential':
          error.value = 'Incorrect password.';
          break;
        case 'auth/too-many-requests':
          error.value = 'Too many failed attempts. Please try again later.';
          break;
        case 'auth/credential-already-in-use':
          // This Google account is already linked to a different Firebase
          // user — likely a mis-provisioning. Drop the pending link so the
          // user isn't stuck on the linking form.
          error.value = 'This Google account is already linked to another user. Contact an administrator.';
          cancelGoogleLink();
          break;
        default:
          error.value = 'Unable to link Google account. Please try again.';
      }
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /** Clear pending Google-link state (user clicked Cancel, or success). */
  function cancelGoogleLink(): void {
    pendingGoogleCredential.value = null;
    linkingEmail.value = null;
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
    linkingEmail,
    linkingRequired,
    initializeAuthListener,
    login,
    loginWithGoogle,
    completeGoogleLink,
    cancelGoogleLink,
    logout,
    refreshToken,
    getIdToken,
    changePassword,
    updateDisplayName
  };
});
