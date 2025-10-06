/**
 * Script to set admin role custom claim for Firebase users
 * Run with: node set-admin-role.js <email>
 */

const admin = require('firebase-admin');
const serviceAccount = require('./src/main/resources/firebase-service-account.json');

// Initialize Firebase Admin
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

async function setAdminRole(email) {
  try {
    // Get user by email
    const user = await admin.auth().getUserByEmail(email);
    console.log(`Found user: ${user.uid} (${user.email})`);

    // Check current custom claims
    const currentClaims = user.customClaims || {};
    console.log('Current custom claims:', JSON.stringify(currentClaims, null, 2));

    // Set admin role
    await admin.auth().setCustomUserClaims(user.uid, {
      ...currentClaims,
      role: 'admin'
    });

    console.log(`✅ Successfully set admin role for ${email}`);

    // Verify the change
    const updatedUser = await admin.auth().getUser(user.uid);
    console.log('Updated custom claims:', JSON.stringify(updatedUser.customClaims, null, 2));

    console.log('\n⚠️  IMPORTANT: User must sign out and sign in again for the role to take effect!');

    process.exit(0);
  } catch (error) {
    console.error('❌ Error:', error.message);
    process.exit(1);
  }
}

// Get email from command line
const email = process.argv[2];

if (!email) {
  console.error('Usage: node set-admin-role.js <email>');
  console.error('Example: node set-admin-role.js admin@albunyaan.tube');
  process.exit(1);
}

setAdminRole(email);
