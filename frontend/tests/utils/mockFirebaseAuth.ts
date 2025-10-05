import { vi } from 'vitest';

/**
 * Mock Firebase Auth for testing.
 *
 * Usage:
 * ```ts
 * import { mockFirebaseAuth } from '../utils/mockFirebaseAuth';
 *
 * const auth = mockFirebaseAuth();
 * auth.currentUser = { uid: 'test-user-123', email: 'test@example.com' };
 * ```
 */
export function mockFirebaseAuth() {
  return {
    currentUser: null,
    onAuthStateChanged: vi.fn((callback) => {
      callback(null); // Simulate no user
      return vi.fn(); // Return unsubscribe function
    }),
    signInWithEmailAndPassword: vi.fn().mockResolvedValue({
      user: {
        uid: 'test-user-123',
        email: 'test@example.com',
        emailVerified: true,
        displayName: 'Test User'
      }
    }),
    signOut: vi.fn().mockResolvedValue(undefined),
    createUserWithEmailAndPassword: vi.fn(),
    sendPasswordResetEmail: vi.fn().mockResolvedValue(undefined)
  };
}

/**
 * Mock authenticated user
 */
export function mockAuthUser(overrides: Partial<any> = {}) {
  return {
    uid: 'test-user-123',
    email: 'test@example.com',
    emailVerified: true,
    displayName: 'Test User',
    photoURL: null,
    phoneNumber: null,
    isAnonymous: false,
    metadata: {
      creationTime: new Date().toISOString(),
      lastSignInTime: new Date().toISOString()
    },
    providerData: [],
    refreshToken: 'mock-refresh-token',
    tenantId: null,
    ...overrides
  };
}

/**
 * Mock ID token result
 */
export function mockIdTokenResult(claims: Record<string, any> = {}) {
  return {
    token: 'mock-id-token',
    expirationTime: new Date(Date.now() + 3600000).toISOString(),
    authTime: new Date().toISOString(),
    issuedAtTime: new Date().toISOString(),
    signInProvider: 'password',
    signInSecondFactor: null,
    claims: {
      sub: 'test-user-123',
      email: 'test@example.com',
      email_verified: true,
      ...claims
    }
  };
}
