export const messages = {
  en: {
    auth: {
      title: 'Albunyaan Tube Admin',
      subtitle: 'Sign in with your administrator account to continue.',
      email: 'Work email',
      password: 'Password',
      signIn: 'Sign in',
      signingIn: 'Signing in…',
      logout: 'Sign out',
      errors: {
        invalidEmail: 'Enter a valid email address.',
        passwordLength: 'Password must be at least 8 characters long.'
      }
    },
    navigation: {
      dashboard: 'Dashboard',
      registry: 'Registry',
      moderation: 'Moderation',
      users: 'Users',
      audit: 'Audit log'
    },
    dashboard: {
      heading: 'Salaam, welcome back',
      subtitle: 'Review the latest moderation activity and registry health.',
      cards: {
        pendingModeration: 'Pending moderation',
        pendingModerationCaption: 'Awaiting review',
        categories: 'Categories',
        categoriesCaption: 'Allow-listed topics',
        moderators: 'Moderators',
        moderatorsCaption: 'Active staff'
      }
    },
    registry: {
      heading: 'Registry workspace',
      description: 'Manage allow-listed channels, playlists, and videos. Detailed tables will appear here in the next milestone.'
    },
    moderation: {
      heading: 'Moderation queue',
      description: 'Track submitted proposals and approve or reject them. Detailed tooling ships in the moderation milestone.'
    },
    users: {
      heading: 'User management',
      description: 'Create and manage admin and moderator accounts. Controls will become available after authentication hardening.'
    },
    audit: {
      heading: 'Audit log',
      description: 'A chronological list of sensitive changes. The viewer will be implemented in a later phase.'
    }
  }
};
