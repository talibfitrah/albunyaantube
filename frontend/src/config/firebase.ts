/**
 * FIREBASE-MIGRATE-07: Firebase Client Configuration
 *
 * Frontend Firebase SDK initialization for authentication.
 * Service account credentials are NOT used here - only public Firebase config.
 */
import { initializeApp } from 'firebase/app';
import { getAuth, connectAuthEmulator } from 'firebase/auth';

// Firebase configuration from project settings
// Replace these values with your actual Firebase project config
const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY || '',
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN || 'albunyaan-tube.firebaseapp.com',
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID || 'albunyaan-tube',
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET || 'albunyaan-tube.appspot.com',
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID || '',
  appId: import.meta.env.VITE_FIREBASE_APP_ID || ''
};

// Initialize Firebase
const app = initializeApp(firebaseConfig);

// Initialize Firebase Authentication
export const auth = getAuth(app);

// Connect to Auth Emulator in development (optional)
if (import.meta.env.DEV && import.meta.env.VITE_USE_FIREBASE_EMULATOR === 'true') {
  connectAuthEmulator(auth, 'http://localhost:9099');
  console.log('🔥 Connected to Firebase Auth Emulator');
}

export default app;
