import '@testing-library/jest-dom';

// Mock Firebase for tests - use demo project config
// This prevents "auth/invalid-api-key" errors in CI
import.meta.env.VITE_FIREBASE_API_KEY = 'demo-test-key';
import.meta.env.VITE_FIREBASE_AUTH_DOMAIN = 'demo-test.firebaseapp.com';
import.meta.env.VITE_FIREBASE_PROJECT_ID = 'demo-test';
import.meta.env.VITE_FIREBASE_STORAGE_BUCKET = 'demo-test.appspot.com';
import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID = '123456789';
import.meta.env.VITE_FIREBASE_APP_ID = '1:123456789:web:abcdef';
import.meta.env.VITE_USE_FIREBASE_EMULATOR = 'false'; // Disable emulator connection attempts in tests
