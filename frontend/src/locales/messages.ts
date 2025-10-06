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
      contentSearch: 'Content Search',
      categories: 'Categories',
      approvals: 'Pending Approvals',
      contentLibrary: 'Content Library',
      registry: 'Registry',
      moderation: 'Moderation',
      exclusions: 'Exclusions',
      bulkImportExport: 'Import/Export',
      users: 'Users',
      audit: 'Audit log',
      activity: 'Activity log',
      settings: 'Settings',
      settingsProfile: 'Profile',
      settingsNotifications: 'Notifications',
      settingsYouTubeAPI: 'YouTube API',
      settingsSystem: 'System',
      home: 'Home',
      channels: 'Channels',
      playlists: 'Playlists',
      videos: 'Videos'
    },
    layout: {
      skipToContent: 'Skip to main content',
      openMenu: 'Open navigation menu',
      closeMenu: 'Close navigation menu'
    },
    dashboard: {
      heading: 'Salaam, welcome back',
      subtitle: 'Review the latest moderation activity and registry health.',
      lastUpdated: 'Last updated {timestamp}',
      cards: {
        pendingModeration: 'Pending moderation',
        pendingModerationCaption: 'Awaiting review',
        categories: 'Categories',
        categoriesCaption: 'Allow-listed topics',
        moderators: 'Moderators',
        moderatorsCaption: 'Active staff',
        deltaUp: 'Up {value} vs previous period',
        deltaDown: 'Down {value} vs previous period',
        deltaFlat: 'No change vs previous period',
        previousValue: 'Previous: {value}',
        categoriesNewThisPeriod: '{count} new this period',
        categoriesPreviousTotal: 'Previously {count}',
        thresholdBreached: 'Action required'
      },
      timeframe: {
        label: 'Timeframe',
        last24h: 'Last 24 hours',
        last7: 'Last 7 days',
        last30: 'Last 30 days'
      },
      error: {
        title: 'Unable to load dashboard metrics.',
        retry: 'Retry'
      },
      warnings: {
        stale: 'Metrics may be out of date. Refresh to update.'
      }
    },
    contentSearch: {
      heading: 'Content Search',
      subtitle: 'Search and discover YouTube content to add for approval',
      searchPlaceholder: 'Search for channels, playlists, or videos...',
      search: 'Search',
      searching: 'Searching...',
      retry: 'Retry',
      add: 'Add for Approval',
      noResults: 'No results found. Try a different search.',
      resultsCount: '{count} results',
      error: 'Failed to search content.',
      types: {
        all: 'All',
        channels: 'Channels',
        playlists: 'Playlists',
        videos: 'Videos'
      },
      filters: {
        type: 'Content Type',
        category: 'Category',
        allCategories: 'All Categories',
        length: 'Video Length',
        anyLength: 'Any Length',
        short: 'Short (< 4 min)',
        medium: 'Medium (4-20 min)',
        long: 'Long (> 20 min)',
        sort: 'Sort By',
        relevant: 'Relevance',
        recent: 'Most Recent',
        popular: 'Most Popular',
        mostRelevant: 'Most Relevant',
        mostRecent: 'Most Recent',
        mostPopular: 'Most Popular',
        topRated: 'Top Rated'
      }
    },
    categories: {
      heading: 'Categories',
      subtitle: 'Manage hierarchical content categories',
      addCategory: 'Add Category',
      addFirst: 'Add First Category',
      addSubcategory: 'Add Subcategory',
      edit: 'Edit',
      delete: 'Delete',
      loading: 'Loading categories...',
      retry: 'Retry',
      empty: 'No categories yet. Add your first category to get started.',
      error: 'Failed to load categories.',
      deleteError: 'Failed to delete category.',
      confirmDelete: 'Are you sure you want to delete this category? All subcategories will also be deleted.',
      dialog: {
        addTitle: 'Add Category',
        editTitle: 'Edit Category',
        name: 'Category Name',
        namePlaceholder: 'Enter category name...',
        nameRequired: 'Category name is required.',
        parent: 'Parent Category',
        icon: 'Icon (optional)',
        iconPlaceholder: 'Enter emoji or icon...',
        displayOrder: 'Display Order',
        cancel: 'Cancel',
        save: 'Save',
        saving: 'Saving...',
        error: 'Failed to save category.'
      }
    },
    approvals: {
      heading: 'Pending Approvals',
      subtitle: 'Review and approve or reject content submissions',
      pending: 'Pending items',
      filters: {
        type: 'Content type',
        category: 'Category',
        allCategories: 'All categories',
        sort: 'Sort by',
        oldest: 'Oldest first',
        newest: 'Newest first'
      },
      types: {
        all: 'All',
        channels: 'Channels',
        playlists: 'Playlists',
        videos: 'Videos',
        channel: 'Channel',
        playlist: 'Playlist',
        video: 'Video'
      },
      categories: 'Categories',
      channel: 'Channel',
      subscribers: 'Subscribers',
      videos: 'Videos',
      noCategories: 'No categories assigned',
      approve: 'Approve',
      approving: 'Approving...',
      approveError: 'Failed to approve submission.',
      reject: 'Reject',
      rejecting: 'Rejecting...',
      rejectError: 'Failed to reject submission.',
      submittedBy: 'Submitted by',
      unknown: 'Unknown',
      loading: 'Loading approvals...',
      retry: 'Retry',
      empty: 'No pending approvals found.',
      error: 'Failed to load approvals.',
      rejectDialog: {
        title: 'Reject Submission',
        reason: 'Rejection reason',
        reasonPlaceholder: 'Provide feedback to help the submitter understand why this was rejected...',
        reasonRequired: 'Please provide a reason for the rejection.',
        cancel: 'Cancel',
        confirm: 'Reject submission',
        rejecting: 'Rejecting...',
        error: 'Failed to reject submission.'
      }
    },
    contentLibrary: {
      heading: 'Content Library',
      subtitle: 'Manage all approved content across channels, playlists, and videos',
      searchPlaceholder: 'Search by title, ID, or description...',
      loading: 'Loading content...',
      retry: 'Retry',
      empty: 'No content found. Try adjusting your filters.',
      error: 'Failed to load content.',
      clearSelection: 'Clear Selection',
      clear: 'Clear',
      bulkActions: 'Bulk Actions',
      viewDetails: 'View Details',
      view: 'View',
      categories: 'Categories',
      assignCategories: 'Assign Categories',
      delete: 'Delete',
      confirmDelete: 'Are you sure you want to delete "{title}"?',
      confirmBulkDelete: 'Are you sure you want to delete {count} items?',
      types: {
        channel: 'Channel',
        playlist: 'Playlist',
        video: 'Video'
      },
      statuses: {
        approved: 'Approved',
        pending: 'Pending',
        rejected: 'Rejected'
      },
      columns: {
        title: 'Title',
        type: 'Type',
        categories: 'Categories',
        status: 'Status',
        dateAdded: 'Date Added',
        actions: 'Actions'
      },
      filters: {
        title: 'Filters',
        contentType: 'Content Type',
        status: 'Status',
        allStatuses: 'All Statuses',
        categories: 'Categories',
        searchCategories: 'Search categories...',
        dateAdded: 'Date Added',
        anyDate: 'Any Date',
        today: 'Today',
        thisWeek: 'This Week',
        thisMonth: 'This Month',
        resetAll: 'Reset All Filters',
        apply: 'Apply Filters'
      },
      sort: {
        newestFirst: 'Newest First',
        oldestFirst: 'Oldest First',
        nameAZ: 'Name (A-Z)',
        nameZA: 'Name (Z-A)'
      },
      bulkMenu: {
        title: 'Bulk Actions',
        approve: 'Approve Selected',
        markPending: 'Mark as Pending',
        assignCategories: 'Assign Categories',
        delete: 'Delete Selected',
        cancel: 'Cancel'
      }
    },
    channelDetails: {
      close: 'Close',
      delete: 'Delete Channel',
      confirmDelete: 'Are you sure you want to delete "{title}"? This action cannot be undone.',
      tabs: {
        overview: 'Overview',
        categories: 'Categories',
        exclusions: 'Exclusions',
        metadata: 'Metadata',
        history: 'History'
      },
      overview: {
        basicInfo: 'Basic Information',
        status: 'Status',
        dateAdded: 'Date Added',
        addedBy: 'Added By',
        unknown: 'Unknown',
        description: 'Description',
        noDescription: 'No description available.',
        youtubeLink: 'YouTube Link'
      },
      categories: {
        assigned: 'Assigned Categories',
        manage: 'Manage Categories',
        noCategories: 'No categories assigned yet.',
        assignFirst: 'Assign Categories',
        remove: 'Remove'
      },
      exclusions: {
        title: 'Excluded Content',
        add: 'Add Exclusion',
        noExclusions: 'No exclusions for this channel.',
        type: 'Type',
        itemTitle: 'Title',
        reason: 'Reason',
        actions: 'Actions',
        remove: 'Remove'
      },
      metadata: {
        title: 'Technical Metadata',
        id: 'Internal ID',
        youtubeId: 'YouTube ID',
        createdAt: 'Created At',
        updatedAt: 'Updated At'
      },
      history: {
        title: 'Activity History',
        noHistory: 'No activity history available.'
      }
    },
    registry: {
      heading: 'Registry workspace',
      description: 'Manage allow-listed channels, playlists, and videos from a single workspace.',
      search: {
        placeholder: 'Search channels, playlists, or videos',
        categoryLabel: 'Filter by category',
        clear: 'Clear search',
        length: {
          label: 'Video length',
          any: 'Any length',
          short: 'Under 4 minutes',
          medium: '4-20 minutes',
          long: 'Over 20 minutes'
        },
        published: {
          label: 'Published date',
          any: 'Any date',
          last24h: 'Last 24 hours',
          last7: 'Last 7 days',
          last30: 'Last 30 days'
        },
        sort: {
          label: 'Sort order',
          default: 'Default order',
          recent: 'Newest first',
          popular: 'Most viewed'
        }
      },
      tabs: {
        channels: 'Channels',
        playlists: 'Playlists',
        videos: 'Videos'
      },
      sections: {
        channels: 'Channels',
        playlists: 'Playlists',
        videos: 'Videos'
      },
      channels: {
        description: 'Monitor approved channels and their category coverage.',
        columns: {
          channel: 'Channel',
          categories: 'Categories',
          subscribers: 'Subscribers'
        }
      },
      playlists: {
        description: 'Review curated playlists and confirm their download status.',
        columns: {
          playlist: 'Playlist',
          owner: 'Owner',
          categories: 'Categories',
          items: 'Items',
          download: 'Download'
        },
        download: {
          available: 'Download enabled',
          blocked: 'Blocked by policy'
        }
      },
      videos: {
        description: 'Inspect allow-listed videos along with channel, duration, and publish date.',
        columns: {
          video: 'Video',
          channel: 'Channel',
          categories: 'Categories',
          duration: 'Duration',
          views: 'Views',
          published: 'Published'
        }
      },
      table: {
        empty: 'No results on this page.',
        error: 'Unable to load {resource}.',
        retry: 'Retry',
        loading: 'Loading…'
      },
      state: {
        loading: 'Fetching registry…',
        emptyChannels: 'No channels match this search.',
        emptyPlaylists: 'No playlists match this search.',
        emptyVideos: 'No videos match this search.'
      },
      actions: {
        include: 'Include',
        exclude: 'Exclude',
        including: 'Including…',
        excluding: 'Excluding…',
        error: 'Unable to update selection. Please try again.'
      },
      pagination: {
        previous: 'Previous',
        next: 'Next',
        showing: 'Showing {count} of {limit} items'
      }
    },
    moderation: {
      heading: 'Moderation queue',
      description: 'Track submitted proposals and approve or reject them. Detailed tooling ships in the moderation milestone.',
      filters: {
        label: 'Status',
        all: 'All',
        pending: 'Pending',
        approved: 'Approved',
        rejected: 'Rejected'
      },
      table: {
        columns: {
          kind: 'Type',
          resource: 'Resource',
          categories: 'Categories',
          proposer: 'Proposed by',
          submitted: 'Submitted',
          notes: 'Notes',
          status: 'Status / Actions'
        },
        empty: 'No proposals match this filter.',
        error: 'Unable to load moderation proposals.',
        loading: 'Loading proposals…'
      },
      actions: {
        approve: 'Approve',
        approving: 'Approving…',
        reject: 'Reject',
        rejecting: 'Rejecting…',
        cancel: 'Cancel',
        confirmReject: 'Reject proposal',
        confirmRejectDescription: 'Provide an optional reason to help the submitter understand the decision.',
        reasonLabel: 'Rejection reason (optional)',
        submitReject: 'Submit decision'
      },
      status: {
        pending: 'Pending',
        approved: 'Approved',
        rejected: 'Rejected'
      },
      kind: {
        CHANNEL: 'Channel',
        PLAYLIST: 'Playlist',
        VIDEO: 'Video'
      },
      decision: {
        approvedBy: 'Approved by {name}',
        rejectedBy: 'Rejected by {name}',
        decidedOn: 'on {date}',
        reason: 'Reason: {reason}'
      },
      errors: {
        actionFailed: 'Unable to update the proposal. Please try again.'
      },
      notesPlaceholder: 'No notes provided.'
    },
    exclusions: {
      heading: 'Exclusions workspace',
      description: 'Manage manual exclusions across channels, playlists, and videos.',
      search: {
        label: 'Search exclusions',
        placeholder: 'Search channels, playlists, or videos'
      },
      filter: {
        label: 'Filter by type',
        all: 'All',
        channels: 'Parent channels',
        parentPlaylist: 'Parent playlists',
        excludePlaylist: 'Excluded playlists',
        excludeVideo: 'Excluded videos'
      },
      table: {
        columns: {
          selection: 'Select row',
          entity: 'Entity',
          type: 'Type',
          parent: 'Parent',
          reason: 'Reason',
          created: 'Created',
          actions: 'Actions'
        },
        noReason: 'No reason provided.',
        empty: 'No exclusions match this filter.',
        rowSelect: 'Select {name} for bulk action',
        loading: 'Loading exclusions…',
        retry: 'Retry',
        error: 'Unable to load exclusions.',
        playlistSummary: 'Playlist exclusion',
        videoSummary: 'Video exclusion'
      },
      actions: {
        add: 'Add exclusion',
        clearSelection: 'Clear selection',
        removeSelected: 'Remove selected',
        remove: 'Remove',
        removing: 'Removing…',
        edit: 'Edit',
        update: 'Update exclusion',
        updating: 'Updating…',
        cancel: 'Cancel',
        create: 'Create exclusion',
        creating: 'Creating…',
        clearSearch: 'Clear search'
      },
      summary: {
        selection: '{count} selected'
      },
      dialog: {
        title: 'Add exclusion',
        description: 'Provide parent and child identifiers to register a manual exclusion.',
        parentTypeLabel: 'Parent type',
        parentChannel: 'Channel',
        parentPlaylist: 'Playlist',
        parentIdLabel: 'Parent ID',
        typeLabel: 'Excluded type',
        targetLabel: 'Excluded ID',
        reasonLabel: 'Reason',
        validation: 'All fields are required.'
      },
      toasts: {
        added: '{name} added to exclusions.',
        removed: 'Exclusion removed.',
        bulkRemoved: '{count} exclusions removed.',
        updated: 'Exclusion updated.'
      },
      errors: {
        createFailed: 'Unable to create exclusion. Please try again.',
        removeFailed: 'Unable to remove exclusion. Please try again.'
      },
      pagination: {
        previous: 'Previous',
        next: 'Next',
        showing: 'Showing {count} of {limit} exclusions'
      }
    },
    users: {
      heading: 'User management',
      description: 'Create, update, and deactivate administrator and moderator accounts.',
      search: {
        label: 'Search users',
        placeholder: 'Search by email or ID',
        clear: 'Clear search'
      },
      filters: {
        role: 'Role',
        roleAll: 'All roles',
        status: 'Status',
        statusAll: 'All statuses'
      },
      roles: {
        admin: 'Administrator',
        moderator: 'Moderator'
      },
      status: {
        active: 'Active',
        disabled: 'Disabled'
      },
      actions: {
        add: 'Add user',
        edit: 'Edit user',
        deactivate: 'Deactivate',
        deactivating: 'Deactivating…',
        activate: 'Activate',
        activating: 'Activating…'
      },
      columns: {
        email: 'Email',
        roles: 'Roles',
        status: 'Status',
        lastLogin: 'Last login',
        created: 'Created',
        actions: 'Actions'
      },
      table: {
        empty: 'No users match this view.',
        loading: 'Loading users…',
        error: 'Unable to load users.',
        retry: 'Retry',
        never: 'Never'
      },
      pagination: {
        previous: 'Previous',
        next: 'Next',
        showing: 'Showing {count} of {limit} users'
      },
      dialogs: {
        actions: {
          cancel: 'Cancel'
        },
        create: {
          title: 'Invite admin or moderator',
          description: 'Enter the email address and assign at least one role. New users receive an email once provisioning completes.',
          email: 'Work email',
          roles: 'Assign roles',
          submit: 'Create user',
          submitting: 'Creating…',
          errors: {
            email: 'Enter an email address.',
            roles: 'Select at least one role.',
            generic: 'Unable to create user. Try again.'
          }
        },
        edit: {
          title: 'Edit {email}',
          description: 'Update role assignments or toggle the account status.',
          roles: 'Roles',
          status: 'Account status',
          submit: 'Save changes',
          submitting: 'Saving…',
          errors: {
            roles: 'Assign at least one role.',
            generic: 'Unable to update user. Try again.'
          }
        }
      },
      toasts: {
        created: '{email} invited.',
        updated: '{email} updated.',
        deactivated: '{email} deactivated.',
        activated: '{email} activated.'
      },
      errors: {
        deactivate: 'Unable to deactivate user. Try again.',
        activate: 'Unable to activate user. Try again.'
      },
      confirm: {
        deactivate: 'Deactivate {email}?'
      }
    },
    audit: {
      heading: 'Audit log',
      description: 'Review sensitive changes across the admin console.',
      filters: {
        actorLabel: 'Filter by actor email',
        actorPlaceholder: 'Filter by actor email',
        actionLabel: 'Filter by action name',
        actionPlaceholder: 'Filter by action name'
      },
      columns: {
        actor: 'Actor',
        action: 'Action',
        entity: 'Entity',
        metadata: 'Metadata',
        timestamp: 'Timestamp'
      },
      table: {
        empty: 'No audit events found.',
        loading: 'Loading audit events…',
        error: 'Unable to load audit events.',
        retry: 'Retry'
      },
      metadata: {
        unavailable: 'Not available'
      },
      roles: {
        none: 'No roles'
      },
      pagination: {
        previous: 'Previous',
        next: 'Next',
        showing: 'Showing {count} of {limit} events'
      }
    },
    activity: {
      heading: 'Activity Log',
      description: 'Track all administrative actions and system events.',
      viewMode: {
        table: 'Table',
        timeline: 'Timeline'
      },
      export: 'Export CSV',
      filters: {
        actor: 'Filter by User',
        actorPlaceholder: 'Enter email address',
        action: 'Action Type',
        allActions: 'All Actions',
        entity: 'Entity Type',
        allEntities: 'All Entities',
        dateRange: 'Time Period',
        today: 'Today',
        lastWeek: 'Last 7 Days',
        lastMonth: 'Last 30 Days',
        allTime: 'All Time',
        clear: 'Clear Filters'
      },
      actions: {
        create: 'Create',
        update: 'Update',
        delete: 'Delete',
        approve: 'Approve',
        reject: 'Reject',
        login: 'Login',
        logout: 'Logout'
      },
      entities: {
        channel: 'Channel',
        playlist: 'Playlist',
        video: 'Video',
        category: 'Category',
        user: 'User'
      },
      columns: {
        timestamp: 'Time',
        actor: 'Actor',
        action: 'Action',
        entity: 'Entity',
        details: 'Details'
      },
      showDetails: 'Show Details',
      empty: 'No activity found for the selected filters.',
      loading: 'Loading activity...',
      retry: 'Retry',
      dateLabels: {
        today: 'Today',
        yesterday: 'Yesterday'
      },
      roles: {
        none: 'No roles'
      },
      metadata: {
        unavailable: 'Not available'
      },
      pagination: {
        previous: 'Previous',
        next: 'Next',
        showing: 'Showing {count} of {limit} events'
      }
    },
    settings: {
      profile: {
        heading: 'Profile Settings',
        description: 'Manage your account profile and security preferences.',
        loading: 'Loading profile...',
        successMessage: 'Profile updated successfully.',
        sections: {
          profileInfo: 'Profile Information',
          changePassword: 'Change Password'
        },
        fields: {
          email: 'Email Address',
          displayName: 'Display Name',
          currentPassword: 'Current Password',
          newPassword: 'New Password',
          confirmPassword: 'Confirm New Password'
        },
        hints: {
          emailImmutable: 'Email address cannot be changed.',
          passwordOptional: 'Leave password fields empty if you don\'t want to change your password.'
        },
        errors: {
          loadFailed: 'Failed to load profile settings. Please try again.',
          saveFailed: 'Failed to save profile settings. Please try again.',
          displayNameRequired: 'Display name is required.',
          displayNameTooShort: 'Display name must be at least 2 characters.',
          currentPasswordRequired: 'Current password is required to change password.',
          newPasswordRequired: 'New password is required.',
          passwordTooShort: 'Password must be at least 8 characters.',
          passwordMismatch: 'Passwords do not match.'
        },
        actions: {
          save: 'Save Changes',
          saving: 'Saving...',
          cancel: 'Cancel'
        }
      },
      notifications: {
        heading: 'Notification Settings',
        description: 'Configure how and when you receive notifications.',
        loading: 'Loading preferences...',
        successMessage: 'Notification preferences updated successfully.',
        sections: {
          email: 'Email Notifications',
          inApp: 'In-App Notifications',
          frequency: 'Notification Frequency'
        },
        preferences: {
          newApprovals: 'New content pending approval',
          approvalDecisions: 'Approval decisions on submitted content',
          categoryChanges: 'Category structure changes',
          userActivity: 'User management actions',
          systemAlerts: 'Critical system alerts',
          weeklyDigest: 'Weekly activity digest'
        },
        hints: {
          newApprovals: 'Notify when new content is submitted for review',
          approvalDecisions: 'Notify when content you submitted is approved or rejected',
          categoryChanges: 'Notify when categories are added, modified, or removed',
          userActivity: 'Notify when users are created, modified, or deactivated',
          systemAlerts: 'Notify about critical system issues and errors',
          weeklyDigest: 'Receive a weekly summary of platform activity',
          frequency: 'Choose how frequently notifications are delivered',
          realtime: 'Receive notifications immediately as events occur',
          hourly: 'Receive a summary of notifications every hour',
          daily: 'Receive a daily digest of all notifications'
        },
        frequency: {
          realtime: 'Real-time',
          hourly: 'Hourly digest',
          daily: 'Daily digest'
        },
        errors: {
          loadFailed: 'Failed to load notification settings. Please try again.',
          saveFailed: 'Failed to save notification settings. Please try again.'
        },
        actions: {
          enableAll: 'Enable all',
          disableAll: 'Disable all',
          save: 'Save Preferences',
          saving: 'Saving...',
          cancel: 'Cancel'
        }
      },
      youtubeApi: {
        heading: 'YouTube API Settings',
        description: 'Manage your YouTube Data API configuration and quota usage.',
        loading: 'Loading API settings...',
        successMessage: 'API settings saved successfully.',
        testSuccess: 'API key is valid and working correctly.',
        testFailure: 'API key validation failed. Please check your key and try again.',
        sections: {
          apiKey: 'API Key Configuration',
          quota: 'Quota Usage',
          documentation: 'Documentation & Resources'
        },
        fields: {
          apiKey: 'YouTube Data API Key'
        },
        hints: {
          apiKey: 'Your YouTube Data API v3 key from Google Cloud Console',
          getApiKey: 'Get your API key from Google Cloud Console',
          quota: 'Monitor your daily API quota usage and limits'
        },
        quota: {
          used: 'Used',
          limit: 'Daily Limit',
          remaining: 'Remaining'
        },
        quotaResetIn: 'Resets in {hours}h {minutes}m',
        warnings: {
          quotaWarning: 'You are approaching your daily quota limit. Consider upgrading or reducing API calls.',
          quotaCritical: 'You are critically close to your daily quota limit. API calls may fail soon.'
        },
        docs: {
          getApiKey: 'Get API Key from Google Cloud',
          apiDocs: 'YouTube Data API Documentation',
          quotaDocs: 'Understanding Quota Costs'
        },
        errors: {
          loadFailed: 'Failed to load API settings. Please try again.',
          saveFailed: 'Failed to save API settings. Please try again.',
          testFailed: 'Failed to test API connection. Please try again.',
          apiKeyRequired: 'API key is required.',
          apiKeyInvalid: 'API key appears to be invalid. Please check the format.'
        },
        actions: {
          show: 'Show API key',
          hide: 'Hide API key',
          test: 'Test Connection',
          testing: 'Testing...',
          save: 'Save API Key',
          saving: 'Saving...'
        }
      },
      system: {
        heading: 'System Settings',
        description: 'Configure system-wide settings and operational parameters.',
        loading: 'Loading system settings...',
        successMessage: 'System settings saved successfully.',
        resetSuccess: 'Settings reset to defaults successfully.',
        confirmReset: 'Are you sure you want to reset all settings to defaults? This action cannot be undone.',
        sections: {
          autoApproval: 'Auto-Approval Rules',
          moderation: 'Content Moderation',
          limits: 'Content Limits',
          auditLog: 'Audit Logging'
        },
        toggles: {
          autoApproveChannels: 'Auto-approve new channels',
          autoApprovePlaylists: 'Auto-approve new playlists',
          autoApproveVideos: 'Auto-approve new videos',
          requireCategoryAssignment: 'Require category assignment',
          enableContentModeration: 'Enable content moderation queue',
          enableAuditLog: 'Enable audit logging'
        },
        fields: {
          contentExpiryDays: 'Content expiry period (days)',
          maxVideosPerChannel: 'Maximum videos per channel',
          auditLogRetentionDays: 'Audit log retention (days)'
        },
        hints: {
          autoApproval: 'Configure which content types are automatically approved without manual review',
          autoApproveChannels: 'Automatically approve channels without manual review',
          autoApprovePlaylists: 'Automatically approve playlists without manual review',
          autoApproveVideos: 'Automatically approve individual videos without manual review',
          moderation: 'Control content moderation workflow and requirements',
          requireCategoryAssignment: 'Require all content to be assigned to a category before approval',
          enableContentModeration: 'Route all new content through the moderation queue',
          limits: 'Set system-wide limits on content volume and retention',
          contentExpiryDays: 'Number of days before inactive content is automatically removed (1-3650)',
          maxVideosPerChannel: 'Maximum number of videos allowed per channel (1-10000)',
          auditLog: 'Configure audit logging for compliance and security',
          enableAuditLog: 'Track all administrative actions in the audit log',
          auditLogRetentionDays: 'Number of days to retain audit log entries (30-3650)'
        },
        errors: {
          loadFailed: 'Failed to load system settings. Please try again.',
          saveFailed: 'Failed to save system settings. Please try again.',
          contentExpiryRange: 'Content expiry must be between 1 and 3650 days.',
          maxVideosRange: 'Max videos per channel must be between 1 and 10000.',
          auditRetentionRange: 'Audit retention must be between 30 and 3650 days.'
        },
        actions: {
          resetToDefaults: 'Reset to Defaults',
          save: 'Save Settings',
          saving: 'Saving...',
          cancel: 'Cancel'
        }
      }
    },
    notifications: {
      heading: 'Notifications',
      togglePanel: 'Toggle notifications panel',
      empty: 'No notifications',
      filters: {
        all: 'All',
        unread: 'Unread'
      },
      types: {
        newApproval: 'New Approval Request',
        categoryChange: 'Category Updated',
        userActivity: 'User Activity',
        systemAlert: 'System Alert'
      },
      time: {
        justNow: 'Just now',
        minutesAgo: '{minutes}m ago',
        hoursAgo: '{hours}h ago',
        daysAgo: '{days}d ago'
      },
      actions: {
        markAllRead: 'Mark all as read',
        close: 'Close',
        viewAll: 'View all activity'
      }
    },
    categoryModal: {
      headingSingle: 'Assign Category',
      headingMulti: 'Assign Categories',
      searchPlaceholder: 'Search categories...',
      loading: 'Loading categories...',
      noResults: 'No categories found.',
      selectedCount: '{count} selected',
      clearSelection: 'Clear selection',
      close: 'Close',
      cancel: 'Cancel',
      assign: 'Assign'
    },
    preferences: {
      localeLabel: 'Interface language',
      locales: {
        en: 'English',
        ar: 'العربية',
        nl: 'Nederlands'
      }
    },
    bulkImportExport: {
      heading: 'Bulk Import/Export',
      subtitle: 'Import or export content and categories in bulk using CSV files',
      export: {
        title: 'Export Data',
        description: 'Download your data as CSV files for backup or analysis',
        selectType: 'Select data type',
        types: {
          content: 'All Content',
          categories: 'Categories'
        },
        contentFilters: 'Content filters',
        includeChannels: 'Include Channels',
        includePlaylists: 'Include Playlists',
        includeVideos: 'Include Videos',
        downloadCSV: 'Download CSV',
        exporting: 'Exporting...',
        success: 'Export completed successfully!',
        error: 'Failed to export data. Please try again.'
      },
      import: {
        title: 'Import Data',
        description: 'Upload CSV files to add content or categories in bulk',
        selectType: 'Select import type',
        types: {
          channels: 'Channels',
          categories: 'Categories'
        },
        chooseFile: 'Choose CSV file',
        fileSize: 'File size',
        uploadCSV: 'Upload and Process',
        importing: 'Importing...',
        results: 'Import Results',
        totalProcessed: 'Total rows processed',
        successful: 'Successful',
        failed: 'Failed',
        row: 'Row',
        status: 'Status',
        message: 'Message',
        statusSuccess: 'Success',
        statusError: 'Error',
        rowSuccess: 'Imported successfully',
        rowError: 'Failed to import',
        importComplete: '{count} items imported successfully',
        error: 'Failed to import file. Please check the format.',
        errorEmptyFile: 'CSV file is empty or invalid'
      },
      templates: {
        title: 'CSV Templates',
        description: 'Download template files to see the required format for imports',
        channels: 'Download Channels Template',
        categories: 'Download Categories Template'
      }
    }
  },
  ar: {
    auth: {
      title: 'إدارة Albunyaan Tube',
      subtitle: 'سجّل الدخول باستخدام حساب المشرف للمتابعة.',
      email: 'البريد الإلكتروني للعمل',
      password: 'كلمة المرور',
      signIn: 'تسجيل الدخول',
      signingIn: 'جارٍ تسجيل الدخول…',
      logout: 'تسجيل الخروج',
      errors: {
        invalidEmail: 'أدخل عنوان بريد إلكتروني صالح.',
        passwordLength: 'يجب أن تتكون كلمة المرور من 8 أحرف على الأقل.'
      }
    },
    navigation: {
      dashboard: 'لوحة التحكم',
      contentSearch: 'البحث عن المحتوى',
      categories: 'الفئات',
      approvals: 'الموافقات المعلقة',
      contentLibrary: 'مكتبة المحتوى',
      registry: 'السجل',
      moderation: 'الإشراف',
      exclusions: 'الاستثناءات',
      bulkImportExport: 'استيراد/تصدير',
      users: 'المستخدمون',
      audit: 'سجل التدقيق',
      activity: 'سجل النشاط',
      home: 'الرئيسية',
      channels: 'القنوات',
      playlists: 'قوائم التشغيل',
      videos: 'الفيديوهات'
    },
    layout: {
      skipToContent: 'تخطي إلى المحتوى الرئيسي'
    },
    dashboard: {
      heading: 'سلام عليكم، مرحباً بعودتك',
      subtitle: 'راجع أحدث نشاط الإشراف وصحة السجل.',
      lastUpdated: 'آخر تحديث {timestamp}',
      cards: {
        pendingModeration: 'طلبات قيد المراجعة',
        pendingModerationCaption: 'بانتظار الموافقة',
        categories: 'الفئات',
        categoriesCaption: 'الموضوعات المسموح بها',
        moderators: 'المشرفون',
        moderatorsCaption: 'أعضاء نشطون',
        deltaUp: 'ارتفاع بنسبة {value} مقارنة بالفترة السابقة',
        deltaDown: 'انخفاض بنسبة {value} مقارنة بالفترة السابقة',
        deltaFlat: 'لا تغيير مقارنة بالفترة السابقة',
        previousValue: 'القيمة السابقة: {value}',
        categoriesNewThisPeriod: 'جديد خلال الفترة: {count}',
        categoriesPreviousTotal: 'الإجمالي السابق: {count}',
        thresholdBreached: 'يتطلب إجراءً'
      },
      timeframe: {
        label: 'الإطار الزمني',
        last24h: 'آخر 24 ساعة',
        last7: 'آخر 7 أيام',
        last30: 'آخر 30 يومًا'
      },
      error: {
        title: 'تعذر تحميل مؤشرات لوحة التحكم.',
        retry: 'إعادة المحاولة'
      },
      warnings: {
        stale: 'قد تكون المؤشرات قديمة. قم بالتحديث للحصول على أحدث القيم.'
      }
    },
    contentSearch: {
      heading: 'البحث عن المحتوى',
      subtitle: 'ابحث واكتشف محتوى YouTube لإضافته للموافقة',
      searchPlaceholder: 'ابحث عن القنوات أو قوائم التشغيل أو الفيديوهات...',
      search: 'بحث',
      searching: 'جارٍ البحث...',
      retry: 'إعادة المحاولة',
      add: 'إضافة للموافقة',
      noResults: 'لا توجد نتائج. جرب بحثًا مختلفًا.',
      resultsCount: '{count} نتائج',
      error: 'فشل البحث عن المحتوى.',
      types: {
        all: 'الكل',
        channels: 'القنوات',
        playlists: 'قوائم التشغيل',
        videos: 'الفيديوهات'
      },
      filters: {
        type: 'نوع المحتوى',
        category: 'الفئة',
        allCategories: 'جميع الفئات',
        length: 'مدة الفيديو',
        anyLength: 'أي مدة',
        short: 'قصير (< 4 دقائق)',
        medium: 'متوسط (4-20 دقيقة)',
        long: 'طويل (> 20 دقيقة)',
        sort: 'ترتيب حسب',
        relevant: 'الأكثر صلة',
        recent: 'الأحدث',
        popular: 'الأكثر شعبية'
      }
    },
    categories: {
      heading: 'الفئات',
      subtitle: 'إدارة فئات المحتوى الهرمية',
      addCategory: 'إضافة فئة',
      addFirst: 'إضافة فئة أولى',
      addSubcategory: 'إضافة فئة فرعية',
      edit: 'تعديل',
      delete: 'حذف',
      loading: 'جارٍ تحميل الفئات...',
      retry: 'إعادة المحاولة',
      empty: 'لا توجد فئات بعد. أضف فئتك الأولى للبدء.',
      error: 'فشل تحميل الفئات.',
      deleteError: 'فشل حذف الفئة.',
      confirmDelete: 'هل أنت متأكد من حذف هذه الفئة؟ سيتم أيضًا حذف جميع الفئات الفرعية.',
      dialog: {
        addTitle: 'إضافة فئة',
        editTitle: 'تعديل فئة',
        name: 'اسم الفئة',
        namePlaceholder: 'أدخل اسم الفئة...',
        nameRequired: 'اسم الفئة مطلوب.',
        parent: 'الفئة الرئيسية',
        icon: 'أيقونة (اختياري)',
        iconPlaceholder: 'أدخل رمز تعبيري أو أيقونة...',
        displayOrder: 'ترتيب العرض',
        cancel: 'إلغاء',
        save: 'حفظ',
        saving: 'جارٍ الحفظ...',
        error: 'فشل حفظ الفئة.'
      }
    },
    approvals: {
      heading: 'الموافقات المعلقة',
      subtitle: 'مراجعة المحتوى المرسل والموافقة عليه أو رفضه',
      pending: 'عناصر قيد الانتظار',
      filters: {
        type: 'نوع المحتوى',
        category: 'الفئة',
        allCategories: 'جميع الفئات',
        sort: 'ترتيب حسب',
        oldest: 'الأقدم أولاً',
        newest: 'الأحدث أولاً'
      },
      types: {
        all: 'الكل',
        channels: 'القنوات',
        playlists: 'قوائم التشغيل',
        videos: 'الفيديوهات',
        channel: 'قناة',
        playlist: 'قائمة تشغيل',
        video: 'فيديو'
      },
      categories: 'الفئات',
      channel: 'القناة',
      subscribers: 'المشتركون',
      videos: 'الفيديوهات',
      noCategories: 'لا توجد فئات محددة',
      approve: 'موافقة',
      approving: 'جارٍ الموافقة...',
      approveError: 'فشل في إكمال الموافقة.',
      reject: 'رفض',
      rejecting: 'جارٍ الرفض...',
      rejectError: 'فشل في رفض المحتوى.',
      submittedBy: 'مقدم من',
      unknown: 'غير معروف',
      loading: 'جارٍ تحميل الموافقات...',
      retry: 'إعادة المحاولة',
      empty: 'لا توجد موافقات معلقة.',
      error: 'فشل تحميل الموافقات.',
      rejectDialog: {
        title: 'رفض الطلب',
        reason: 'سبب الرفض',
        reasonPlaceholder: 'قدم تعليقات لمساعدة المرسل على فهم سبب الرفض...',
        reasonRequired: 'يرجى توضيح سبب الرفض.',
        cancel: 'إلغاء',
        confirm: 'تأكيد الرفض',
        rejecting: 'جارٍ الرفض...',
        error: 'فشل رفض الطلب.'
      }
    },
    registry: {
      heading: 'مساحة عمل السجل',
      description: 'أدر القنوات وقوائم التشغيل والفيديوهات المسموح بها من مكان واحد.',
      search: {
        placeholder: 'ابحث في القنوات أو قوائم التشغيل أو الفيديوهات',
        categoryLabel: 'التصفية حسب الفئة',
        clear: 'مسح البحث',
        length: {
          label: 'مدة الفيديو',
          any: 'أي مدة',
          short: 'أقل من 4 دقائق',
          medium: '4-20 دقيقة',
          long: 'أكثر من 20 دقيقة'
        },
        published: {
          label: 'تاريخ النشر',
          any: 'أي تاريخ',
          last24h: 'آخر 24 ساعة',
          last7: 'آخر 7 أيام',
          last30: 'آخر 30 يومًا'
        },
        sort: {
          label: 'ترتيب الفرز',
          default: 'الترتيب الافتراضي',
          recent: 'الأحدث أولاً',
          popular: 'الأكثر مشاهدة'
        }
      },
      tabs: {
        channels: 'القنوات',
        playlists: 'قوائم التشغيل',
        videos: 'الفيديوهات'
      },
      sections: {
        channels: 'القنوات',
        playlists: 'قوائم التشغيل',
        videos: 'الفيديوهات'
      },
      channels: {
        description: 'راقب القنوات المعتمدة وتغطيتها للفئات.',
        columns: {
          channel: 'القناة',
          categories: 'الفئات',
          subscribers: 'المشتركون'
        }
      },
      playlists: {
        description: 'راجع القوائم المنسقة وتحقق من حالة التنزيل.',
        columns: {
          playlist: 'قائمة التشغيل',
          owner: 'المالك',
          categories: 'الفئات',
          items: 'العناصر',
          download: 'التنزيل'
        },
        download: {
          available: 'التنزيل متاح',
          blocked: 'محظور حسب السياسة'
        }
      },
      videos: {
        description: 'تفقد الفيديوهات المسموح بها مع القناة والمدة وتاريخ النشر.',
        columns: {
          video: 'الفيديو',
          channel: 'القناة',
          categories: 'الفئات',
          duration: 'المدة',
          views: 'المشاهدات',
          published: 'تاريخ النشر'
        }
      },
      table: {
        empty: 'لا توجد نتائج في هذه الصفحة.',
        error: 'تعذر تحميل {resource}.',
        retry: 'إعادة المحاولة',
        loading: 'جارٍ التحميل…'
      },
      state: {
        loading: 'جارٍ جلب السجل…',
        emptyChannels: 'لا توجد قنوات تطابق هذا البحث.',
        emptyPlaylists: 'لا توجد قوائم تطابق هذا البحث.',
        emptyVideos: 'لا توجد فيديوهات تطابق هذا البحث.'
      },
      actions: {
        include: 'تضمين',
        exclude: 'استبعاد',
        including: 'جارٍ التضمين…',
        excluding: 'جارٍ الاستبعاد…',
        error: 'تعذر تحديث الاختيار. حاول مرة أخرى.'
      },
      pagination: {
        previous: 'السابق',
        next: 'التالي',
        showing: 'إظهار {count} من {limit} عناصر'
      }
    },
    moderation: {
      heading: 'قائمة الإشراف',
      description: 'تابع المقترحات وأقرها أو ارفضها.',
      filters: {
        label: 'الحالة',
        all: 'الكل',
        pending: 'قيد الانتظار',
        approved: 'مقبول',
        rejected: 'مرفوض'
      },
      table: {
        columns: {
          kind: 'النوع',
          resource: 'المورد',
          categories: 'الفئات',
          proposer: 'المقترح',
          submitted: 'تاريخ الإرسال',
          notes: 'ملاحظات',
          status: 'الحالة / الإجراءات'
        },
        empty: 'لا توجد مقترحات تطابق هذا التصفية.',
        error: 'تعذر تحميل المقترحات.',
        loading: 'جارٍ تحميل المقترحات…'
      },
      actions: {
        approve: 'موافقة',
        approving: 'جارٍ الموافقة…',
        reject: 'رفض',
        rejecting: 'جارٍ الرفض…',
        cancel: 'إلغاء',
        confirmReject: 'رفض المقترح',
        confirmRejectDescription: 'أضف سببًا اختياريًا لمساعدة المرسل على فهم القرار.',
        reasonLabel: 'سبب الرفض (اختياري)',
        submitReject: 'إرسال القرار'
      },
      status: {
        pending: 'قيد الانتظار',
        approved: 'مقبول',
        rejected: 'مرفوض'
      },
      kind: {
        CHANNEL: 'قناة',
        PLAYLIST: 'قائمة تشغيل',
        VIDEO: 'فيديو'
      },
      decision: {
        approvedBy: 'تمت الموافقة بواسطة {name}',
        rejectedBy: 'تم الرفض بواسطة {name}',
        decidedOn: 'في {date}',
        reason: 'السبب: {reason}'
      },
      errors: {
        actionFailed: 'تعذر تحديث المقترح. حاول مرة أخرى.'
      },
      notesPlaceholder: 'لا توجد ملاحظات.'
    },
    exclusions: {
      heading: 'مساحة الاستثناءات',
      description: 'إدارة الاستثناءات اليدوية عبر القنوات وقوائم التشغيل والفيديوهات.',
      search: {
        label: 'ابحث في الاستثناءات',
        placeholder: 'ابحث عن القنوات أو قوائم التشغيل أو الفيديوهات'
      },
      filter: {
        label: 'التصفية حسب النوع',
        all: 'الكل',
        channels: 'قنوات المصدر الرئيسي',
        parentPlaylist: 'قوائم تشغيل المصدر الرئيسي',
        excludePlaylist: 'قوائم التشغيل المستبعدة',
        excludeVideo: 'الفيديوهات المستبعدة'
      },
      table: {
        columns: {
          selection: 'تحديد الصف',
          entity: 'العنصر',
          type: 'النوع',
          parent: 'المصدر الرئيسي',
          reason: 'السبب',
          created: 'تاريخ الإنشاء',
          actions: 'الإجراءات'
        },
        noReason: 'لا يوجد سبب مذكور.',
        empty: 'لا توجد استثناءات تطابق هذا التصفية.',
        rowSelect: 'تحديد {name} لتطبيق الإجراءات المجمعة',
        loading: 'جارٍ تحميل الاستثناءات…',
        retry: 'إعادة المحاولة',
        error: 'تعذر تحميل الاستثناءات.',
        playlistSummary: 'استثناء قائمة تشغيل',
        videoSummary: 'استثناء فيديو'
      },
      actions: {
        add: 'إضافة استثناء',
        clearSelection: 'مسح التحديد',
        removeSelected: 'إزالة التحديد',
        remove: 'إزالة',
        removing: 'جارٍ الإزالة…',
        edit: 'تعديل',
        update: 'تحديث الاستثناء',
        updating: 'جارٍ التحديث…',
        cancel: 'إلغاء',
        create: 'إنشاء استثناء',
        creating: 'جارٍ الإنشاء…',
        clearSearch: 'مسح البحث'
      },
      summary: {
        selection: 'تم تحديد {count}'
      },
      dialog: {
        title: 'إضافة استثناء',
        description: 'قدم معرّفات المصدر الأصلي والعنصر المستبعد لتسجيل استثناء يدوي.',
        parentTypeLabel: 'نوع المصدر الرئيسي',
        parentChannel: 'قناة',
        parentPlaylist: 'قائمة تشغيل',
        parentIdLabel: 'معرّف المصدر الرئيسي',
        typeLabel: 'نوع العنصر المستبعد',
        targetLabel: 'معرّف العنصر المستبعد',
        reasonLabel: 'السبب',
        validation: 'جميع الحقول مطلوبة.'
      },
      toasts: {
        added: 'تمت إضافة {name} إلى الاستثناءات.',
        removed: 'تمت إزالة الاستثناء.',
        bulkRemoved: 'تمت إزالة {count} من الاستثناءات.',
        updated: 'تم تحديث الاستثناء.'
      },
      errors: {
        createFailed: 'تعذر إنشاء الاستثناء. حاول مرة أخرى.',
        removeFailed: 'تعذر إزالة الاستثناء. حاول مرة أخرى.'
      },
      pagination: {
        previous: 'السابق',
        next: 'التالي',
        showing: 'إظهار {count} من أصل {limit} استثناءات'
      }
    },
    users: {
      heading: 'إدارة المستخدمين',
      description: 'أنشئ أو حدّث أو عطّل حسابات المشرفين والمراقبين.',
      search: {
        label: 'بحث عن المستخدمين',
        placeholder: 'ابحث بالبريد الإلكتروني أو المعرّف',
        clear: 'مسح البحث'
      },
      filters: {
        role: 'الدور',
        roleAll: 'جميع الأدوار',
        status: 'الحالة',
        statusAll: 'كل الحالات'
      },
      roles: {
        admin: 'مشرف',
        moderator: 'مراقب'
      },
      status: {
        active: 'نشط',
        disabled: 'معطّل'
      },
      actions: {
        add: 'إضافة مستخدم',
        edit: 'تعديل المستخدم',
        deactivate: 'تعطيل',
        deactivating: 'جارٍ التعطيل…',
        activate: 'تنشيط',
        activating: 'جارٍ التنشيط…'
      },
      columns: {
        email: 'البريد الإلكتروني',
        roles: 'الأدوار',
        status: 'الحالة',
        lastLogin: 'آخر تسجيل دخول',
        created: 'تاريخ الإنشاء',
        actions: 'إجراءات'
      },
      table: {
        empty: 'لا يوجد مستخدمون مطابقون.',
        loading: 'جارٍ تحميل المستخدمين…',
        error: 'تعذّر تحميل المستخدمين.',
        retry: 'إعادة المحاولة',
        never: 'لم يحدث'
      },
      pagination: {
        previous: 'السابق',
        next: 'التالي',
        showing: 'يتم عرض {count} من أصل {limit} مستخدمين'
      },
      dialogs: {
        actions: {
          cancel: 'إلغاء'
        },
        create: {
          title: 'دعوة مشرف أو مراقب',
          description: 'أدخل البريد الإلكتروني وعيِّن دورًا واحدًا على الأقل. سيتلقى المستخدم الجديد رسالة عند تجهيز الحساب.',
          email: 'البريد الإلكتروني للعمل',
          roles: 'تعيين الأدوار',
          submit: 'إنشاء مستخدم',
          submitting: 'جارٍ الإنشاء…',
          errors: {
            email: 'أدخل البريد الإلكتروني.',
            roles: 'اختر دورًا واحدًا على الأقل.',
            generic: 'تعذّر إنشاء المستخدم. حاول مجددًا.'
          }
        },
        edit: {
          title: 'تعديل {email}',
          description: 'حدّث الأدوار أو غيّر حالة الحساب.',
          roles: 'الأدوار',
          status: 'حالة الحساب',
          submit: 'حفظ التغييرات',
          submitting: 'جارٍ الحفظ…',
          errors: {
            roles: 'عيّن دورًا واحدًا على الأقل.',
            generic: 'تعذّر تحديث المستخدم. حاول مجددًا.'
          }
        }
      },
      toasts: {
        created: 'تمت دعوة {email}.',
        updated: 'تم تحديث {email}.',
        deactivated: 'تم تعطيل {email}.',
        activated: 'تم تنشيط {email}.'
      },
      errors: {
        deactivate: 'تعذّر تعطيل المستخدم. حاول مجددًا.',
        activate: 'تعذّر تنشيط المستخدم. حاول مجددًا.'
      },
      confirm: {
        deactivate: 'هل ترغب في تعطيل {email}؟'
      }
    },
    audit: {
      heading: 'سجل التدقيق',
      description: 'راجع التغييرات الحساسة ضمن لوحة الإدارة.',
      filters: {
        actorLabel: 'تصفية حسب البريد الإلكتروني للمنفّذ',
        actorPlaceholder: 'تصفية حسب البريد الإلكتروني للمنفّذ',
        actionLabel: 'تصفية حسب اسم الإجراء',
        actionPlaceholder: 'تصفية حسب اسم الإجراء'
      },
      columns: {
        actor: 'المنفّذ',
        action: 'الإجراء',
        entity: 'الكيان',
        metadata: 'البيانات الوصفية',
        timestamp: 'الوقت'
      },
      table: {
        empty: 'لا توجد أحداث تدقيق.',
        loading: 'جارٍ تحميل أحداث التدقيق…',
        error: 'تعذّر تحميل أحداث التدقيق.',
        retry: 'إعادة المحاولة'
      },
      metadata: {
        unavailable: 'غير متوفّر'
      },
      roles: {
        none: 'بدون أدوار'
      },
      pagination: {
        previous: 'السابق',
        next: 'التالي',
        showing: 'عرض {count} من {limit} أحداث'
      }
    },
    activity: {
      heading: 'سجل النشاط',
      description: 'تتبع جميع الإجراءات الإدارية وأحداث النظام.',
      viewMode: {
        table: 'جدول',
        timeline: 'خط زمني'
      },
      export: 'تصدير CSV',
      filters: {
        actor: 'تصفية حسب المستخدم',
        actorPlaceholder: 'أدخل عنوان البريد الإلكتروني',
        action: 'نوع الإجراء',
        allActions: 'جميع الإجراءات',
        entity: 'نوع الكيان',
        allEntities: 'جميع الكيانات',
        dateRange: 'الفترة الزمنية',
        today: 'اليوم',
        lastWeek: 'آخر 7 أيام',
        lastMonth: 'آخر 30 يوم',
        allTime: 'كل الأوقات',
        clear: 'مسح الفلاتر'
      },
      actions: {
        create: 'إنشاء',
        update: 'تحديث',
        delete: 'حذف',
        approve: 'موافقة',
        reject: 'رفض',
        login: 'تسجيل الدخول',
        logout: 'تسجيل الخروج'
      },
      entities: {
        video: 'فيديو',
        playlist: 'قائمة تشغيل',
        channel: 'قناة',
        category: 'فئة',
        user: 'مستخدم',
        settings: 'الإعدادات'
      },
      table: {
        timestamp: 'التاريخ والوقت',
        actor: 'الممثل',
        action: 'الإجراء',
        entity: 'الكيان',
        details: 'التفاصيل'
      },
      noResults: 'لم يتم العثور على إدخالات نشاط. جرب تعديل الفلاتر.',
      loadingMore: 'تحميل المزيد من الإدخالات...',
      loadMore: 'تحميل المزيد',
      reachedEnd: 'لقد وصلت إلى نهاية سجل النشاط.',
      error: 'فشل تحميل سجل النشاط. يرجى المحاولة مرة أخرى.',
      retry: 'إعادة المحاولة'
    },
    preferences: {
      localeLabel: 'لغة الواجهة',
      locales: {
        en: 'الإنجليزية',
        ar: 'العربية',
        nl: 'الهولندية'
      }
    },
    bulkImportExport: {
      heading: 'الاستيراد/التصدير المجمّع',
      subtitle: 'استورد أو صدّر المحتوى والفئات بشكل مجمّع باستخدام ملفات CSV',
      export: {
        title: 'تصدير البيانات',
        description: 'قم بتنزيل بياناتك كملفات CSV للنسخ الاحتياطي أو التحليل',
        selectType: 'اختر نوع البيانات',
        types: {
          content: 'جميع المحتوى',
          categories: 'الفئات'
        },
        contentFilters: 'مرشحات المحتوى',
        includeChannels: 'تضمين القنوات',
        includePlaylists: 'تضمين قوائم التشغيل',
        includeVideos: 'تضمين الفيديوهات',
        downloadCSV: 'تنزيل CSV',
        exporting: 'جارٍ التصدير...',
        success: 'تم التصدير بنجاح!',
        error: 'فشل تصدير البيانات. يرجى المحاولة مرة أخرى.'
      },
      import: {
        title: 'استيراد البيانات',
        description: 'قم برفع ملفات CSV لإضافة محتوى أو فئات بشكل مجمّع',
        selectType: 'اختر نوع الاستيراد',
        types: {
          channels: 'القنوات',
          categories: 'الفئات'
        },
        chooseFile: 'اختر ملف CSV',
        fileSize: 'حجم الملف',
        uploadCSV: 'رفع ومعالجة',
        importing: 'جارٍ الاستيراد...',
        results: 'نتائج الاستيراد',
        totalProcessed: 'إجمالي الصفوف المعالجة',
        successful: 'ناجح',
        failed: 'فشل',
        row: 'الصف',
        status: 'الحالة',
        message: 'الرسالة',
        statusSuccess: 'نجاح',
        statusError: 'خطأ',
        rowSuccess: 'تم الاستيراد بنجاح',
        rowError: 'فشل الاستيراد',
        importComplete: 'تم استيراد {count} عنصر بنجاح',
        error: 'فشل استيراد الملف. يرجى التحقق من التنسيق.',
        errorEmptyFile: 'ملف CSV فارغ أو غير صالح'
      },
      templates: {
        title: 'قوالب CSV',
        description: 'قم بتنزيل ملفات القوالب لمعرفة التنسيق المطلوب للاستيراد',
        channels: 'تنزيل قالب القنوات',
        categories: 'تنزيل قالب الفئات'
      }
    }
  },
  nl: {
    auth: {
      title: 'Albunyaan Tube Beheer',
      subtitle: 'Meld je aan met je beheerdersaccount om door te gaan.',
      email: 'Werk e-mailadres',
      password: 'Wachtwoord',
      signIn: 'Inloggen',
      signingIn: 'Bezig met inloggen…',
      logout: 'Afmelden',
      errors: {
        invalidEmail: 'Voer een geldig e-mailadres in.',
        passwordLength: 'Het wachtwoord moet minstens 8 tekens bevatten.'
      }
    },
    navigation: {
      dashboard: 'Dashboard',
      contentSearch: 'Zoeken naar inhoud',
      categories: 'Categorieën',
      approvals: 'Goedkeuringen in behandeling',
      contentLibrary: 'Inhoudsbibliotheek',
      registry: 'Registerbeheer',
      moderation: 'Moderatie',
      exclusions: 'Uitzonderingen',
      bulkImportExport: 'Importeren/Exporteren',
      users: 'Gebruikers',
      audit: 'Auditlogboek',
      activity: 'Activiteitenlogboek',
      home: 'Home',
      channels: 'Kanalen',
      playlists: 'Afspeellijsten',
      videos: 'Video\'s'
    },
    layout: {
      skipToContent: 'Ga naar hoofdinhoud'
    },
    dashboard: {
      heading: 'Salaam, welkom terug',
      subtitle: 'Bekijk de laatste moderatie-activiteit en gezondheid van het register.',
      lastUpdated: 'Laatst bijgewerkt {timestamp}',
      cards: {
        pendingModeration: 'Openstaande moderatie',
        pendingModerationCaption: 'Wacht op beoordeling',
        categories: 'Categorieën',
        categoriesCaption: 'Toegestane onderwerpen',
        moderators: 'Moderators',
        moderatorsCaption: 'Actieve medewerkers',
        deltaUp: 'Stijging van {value} ten opzichte van de vorige periode',
        deltaDown: 'Daling van {value} ten opzichte van de vorige periode',
        deltaFlat: 'Geen verandering ten opzichte van de vorige periode',
        previousValue: 'Vorige waarde: {value}',
        categoriesNewThisPeriod: 'Nieuw in deze periode: {count}',
        categoriesPreviousTotal: 'Vorige totaal: {count}',
        thresholdBreached: 'Actie nodig'
      },
      timeframe: {
        label: 'Tijdsperiode',
        last24h: 'Laatste 24 uur',
        last7: 'Laatste 7 dagen',
        last30: 'Laatste 30 dagen'
      },
      error: {
        title: 'Dashboardstatistieken kunnen niet worden geladen.',
        retry: 'Opnieuw proberen'
      },
      warnings: {
        stale: 'Statistieken kunnen verouderd zijn. Vernieuw om bij te werken.'
      }
    },
    contentSearch: {
      heading: 'Zoeken naar inhoud',
      subtitle: 'Zoek en ontdek YouTube-inhoud om toe te voegen voor goedkeuring',
      searchPlaceholder: 'Zoek naar kanalen, afspeellijsten of video\'s...',
      search: 'Zoeken',
      searching: 'Zoeken...',
      retry: 'Opnieuw proberen',
      add: 'Toevoegen voor goedkeuring',
      noResults: 'Geen resultaten gevonden. Probeer een andere zoekopdracht.',
      resultsCount: '{count} resultaten',
      error: 'Zoeken naar inhoud is mislukt.',
      types: {
        all: 'Alle',
        channels: 'Kanalen',
        playlists: 'Afspeellijsten',
        videos: 'Video\'s'
      },
      filters: {
        type: 'Type inhoud',
        category: 'Categorie',
        allCategories: 'Alle categorieën',
        length: 'Videolengte',
        anyLength: 'Elke lengte',
        short: 'Kort (< 4 min)',
        medium: 'Middel (4-20 min)',
        long: 'Lang (> 20 min)',
        sort: 'Sorteren op',
        relevant: 'Relevantie',
        recent: 'Meest recent',
        popular: 'Meest populair'
      }
    },
    categories: {
      heading: 'Categorieën',
      subtitle: 'Beheer hiërarchische inhoudcategorieën',
      addCategory: 'Categorie toevoegen',
      addFirst: 'Eerste categorie toevoegen',
      addSubcategory: 'Subcategorie toevoegen',
      edit: 'Bewerken',
      delete: 'Verwijderen',
      loading: 'Categorieën laden...',
      retry: 'Opnieuw proberen',
      empty: 'Nog geen categorieën. Voeg je eerste categorie toe om te beginnen.',
      error: 'Categorieën kunnen niet worden geladen.',
      deleteError: 'Categorie kan niet worden verwijderd.',
      confirmDelete: 'Weet je zeker dat je deze categorie wilt verwijderen? Alle subcategorieën worden ook verwijderd.',
      dialog: {
        addTitle: 'Categorie toevoegen',
        editTitle: 'Categorie bewerken',
        name: 'Categorienaam',
        namePlaceholder: 'Voer categorienaam in...',
        nameRequired: 'Categorienaam is verplicht.',
        parent: 'Bovenliggende categorie',
        icon: 'Icoon (optioneel)',
        iconPlaceholder: 'Voer emoji of icoon in...',
        displayOrder: 'Weergavevolgorde',
        cancel: 'Annuleren',
        save: 'Opslaan',
        saving: 'Opslaan...',
        error: 'Categorie opslaan is mislukt.'
      }
    },
    approvals: {
      heading: 'Goedkeuringen in behandeling',
      subtitle: 'Beoordeel en keur ingediende inhoud goed of af',
      pending: 'Items in behandeling',
      filters: {
        type: 'Type inhoud',
        category: 'Categorie',
        allCategories: 'Alle categorieën',
        sort: 'Sorteren op',
        oldest: 'Oudste eerst',
        newest: 'Nieuwste eerst'
      },
      types: {
        all: 'Alles',
        channels: 'Kanalen',
        playlists: 'Afspeellijsten',
        videos: 'Video\'s',
        channel: 'Kanaal',
        playlist: 'Afspeellijst',
        video: 'Video'
      },
      categories: 'Categorieën',
      channel: 'Kanaal',
      subscribers: 'Abonnees',
      videos: 'Video\'s',
      noCategories: 'Geen categorieën toegewezen',
      approve: 'Goedkeuren',
      approving: 'Goedkeuren...',
      approveError: 'Goedkeuren is mislukt.',
      reject: 'Afwijzen',
      rejecting: 'Afwijzen...',
      rejectError: 'Afwijzen is mislukt.',
      submittedBy: 'Ingediend door',
      unknown: 'Onbekend',
      loading: 'Goedkeuringen laden...',
      retry: 'Opnieuw proberen',
      empty: 'Geen goedkeuringen in behandeling.',
      error: 'Goedkeuringen kunnen niet worden geladen.',
      rejectDialog: {
        title: 'Indiening afwijzen',
        reason: 'Reden voor afwijzing',
        reasonPlaceholder: 'Geef feedback om de indiener te helpen begrijpen waarom dit is afgewezen...',
        reasonRequired: 'Geef een reden voor de afwijzing.',
        cancel: 'Annuleren',
        confirm: 'Indiening afwijzen',
        rejecting: 'Afwijzen...',
        error: 'Indiening afwijzen is mislukt.'
      }
    },
    registry: {
      heading: 'Registerwerkruimte',
      description: 'Beheer goedgekeurde kanalen, afspeellijsten en video\'s vanuit één workspace.',
      search: {
        placeholder: 'Zoek naar kanalen, afspeellijsten of video\'s',
        categoryLabel: 'Filter op categorie',
        clear: 'Zoekopdracht wissen',
        length: {
          label: 'Videolengte',
          any: 'Elke lengte',
          short: 'Korter dan 4 minuten',
          medium: '4-20 minuten',
          long: 'Langer dan 20 minuten'
        },
        published: {
          label: 'Publicatiedatum',
          any: 'Elke datum',
          last24h: 'Laatste 24 uur',
          last7: 'Laatste 7 dagen',
          last30: 'Laatste 30 dagen'
        },
        sort: {
          label: 'Sorteervolgorde',
          default: 'Standaardvolgorde',
          recent: 'Nieuwste eerst',
          popular: 'Meest bekeken'
        }
      },
      tabs: {
        channels: 'Kanalen',
        playlists: 'Afspeellijsten',
        videos: 'Video\'s'
      },
      sections: {
        channels: 'Kanalen',
        playlists: 'Afspeellijsten',
        videos: 'Video\'s'
      },
      channels: {
        description: 'Bewaking van goedgekeurde kanalen en hun categoriedekking.',
        columns: {
          channel: 'Kanaal',
          categories: 'Categorieën',
          subscribers: 'Abonnees'
        }
      },
      playlists: {
        description: 'Controleer samengestelde afspeellijsten en bevestig de downloadstatus.',
        columns: {
          playlist: 'Afspeellijst',
          owner: 'Eigenaar',
          categories: 'Categorieën',
          items: 'Items',
          download: 'Download'
        },
        download: {
          available: 'Download beschikbaar',
          blocked: 'Geblokkeerd door beleid'
        }
      },
      videos: {
        description: 'Bekijk goedgekeurde video\'s met kanaal, duur en publicatiedatum.',
        columns: {
          video: 'Video',
          channel: 'Kanaal',
          categories: 'Categorieën',
          duration: 'Duur',
          views: 'Weergaven',
          published: 'Gepubliceerd'
        }
      },
      table: {
        empty: 'Geen resultaten op deze pagina.',
        error: '{resource} kan niet worden geladen.',
        retry: 'Opnieuw proberen',
        loading: 'Laden…'
      },
      state: {
        loading: 'Register wordt geladen…',
        emptyChannels: 'Geen kanalen voldoen aan deze zoekopdracht.',
        emptyPlaylists: 'Geen afspeellijsten voldoen aan deze zoekopdracht.',
        emptyVideos: 'Geen video\'s voldoen aan deze zoekopdracht.'
      },
      actions: {
        include: 'Opnemen',
        exclude: 'Uitsluiten',
        including: 'Wordt opgenomen…',
        excluding: 'Wordt uitgesloten…',
        error: 'Selectie kan niet worden bijgewerkt. Probeer het opnieuw.'
      },
      pagination: {
        previous: 'Vorige',
        next: 'Volgende',
        showing: '{count} van {limit} items getoond'
      }
    },
    moderation: {
      heading: 'Moderatie wachtrij',
      description: 'Volg ingediende voorstellen en keur ze goed of af.',
      filters: {
        label: 'Status',
        all: 'Alle',
        pending: 'In behandeling',
        approved: 'Goedgekeurd',
        rejected: 'Afgewezen'
      },
      table: {
        columns: {
          kind: 'Type',
          resource: 'Item',
          categories: 'Categorieën',
          proposer: 'Ingediend door',
          submitted: 'Ingediend',
          notes: 'Notities',
          status: 'Status / Acties'
        },
        empty: 'Geen voorstellen gevonden voor dit filter.',
        error: 'Kan moderatievoorstellen niet laden.',
        loading: 'Voorstellen laden…'
      },
      actions: {
        approve: 'Goedkeuren',
        approving: 'Bezig met goedkeuren…',
        reject: 'Afwijzen',
        rejecting: 'Bezig met afwijzen…',
        cancel: 'Annuleren',
        confirmReject: 'Voorstel afwijzen',
        confirmRejectDescription: 'Voeg een optionele reden toe zodat de indiener het besluit begrijpt.',
        reasonLabel: 'Reden voor afwijzing (optioneel)',
        submitReject: 'Beslissing verzenden'
      },
      status: {
        pending: 'In behandeling',
        approved: 'Goedgekeurd',
        rejected: 'Afgewezen'
      },
      kind: {
        CHANNEL: 'Kanaal',
        PLAYLIST: 'Afspeellijst',
        VIDEO: 'Video'
      },
      decision: {
        approvedBy: 'Goedgekeurd door {name}',
        rejectedBy: 'Afgewezen door {name}',
        decidedOn: 'op {date}',
        reason: 'Reden: {reason}'
      },
      errors: {
        actionFailed: 'Het voorstel kan niet worden bijgewerkt. Probeer het opnieuw.'
      },
      notesPlaceholder: 'Geen notities toegevoegd.'
    },
    exclusions: {
      heading: 'Workspace uitzonderingen',
      description: 'Beheer handmatige uitzonderingen voor kanalen, afspeellijsten en video\'s.',
      search: {
        label: 'Zoek in uitzonderingen',
        placeholder: 'Zoek naar kanalen, afspeellijsten of video\'s'
      },
      filter: {
        label: 'Filter op type',
        all: 'Alles',
        channels: 'Bovenliggende kanalen',
        parentPlaylist: 'Bovenliggende afspeellijsten',
        excludePlaylist: 'Uitgesloten afspeellijsten',
        excludeVideo: 'Uitgesloten video\'s'
      },
      table: {
        columns: {
          selection: 'Selecteer rij',
          entity: 'Item',
          type: 'Type',
          parent: 'Bovenliggend',
          reason: 'Reden',
          created: 'Gemaakt',
          actions: 'Acties'
        },
        noReason: 'Geen reden opgegeven.',
        empty: 'Geen uitzonderingen voor dit filter.',
        rowSelect: '{name} selecteren voor bulkactie',
        loading: 'Uitzonderingen laden…',
        retry: 'Opnieuw proberen',
        error: 'Kan uitzonderingen niet laden.',
        playlistSummary: 'Uitzondering voor afspeellijst',
        videoSummary: 'Uitzondering voor video'
      },
      actions: {
        add: 'Uitzondering toevoegen',
        clearSelection: 'Selectie wissen',
        removeSelected: 'Selectie verwijderen',
        remove: 'Verwijderen',
        removing: 'Bezig met verwijderen…',
        edit: 'Bewerken',
        update: 'Uitzondering bijwerken',
        updating: 'Bezig met bijwerken…',
        cancel: 'Annuleren',
        create: 'Uitzondering maken',
        creating: 'Bezig met maken…',
        clearSearch: 'Zoekopdracht wissen'
      },
      summary: {
        selection: '{count} geselecteerd'
      },
      dialog: {
        title: 'Uitzondering toevoegen',
        description: 'Geef de bovenliggende en uitgesloten id\'s op om een handmatige uitzondering te registreren.',
        parentTypeLabel: 'Type bovenliggende bron',
        parentChannel: 'Kanaal',
        parentPlaylist: 'Afspeellijst',
        parentIdLabel: 'Bovenliggend ID',
        typeLabel: 'Type uitgesloten item',
        targetLabel: 'Uitgesloten ID',
        reasonLabel: 'Reden',
        validation: 'Alle velden zijn verplicht.'
      },
      toasts: {
        added: '{name} toegevoegd aan uitzonderingen.',
        removed: 'Uitzondering verwijderd.',
        bulkRemoved: '{count} uitzonderingen verwijderd.',
        updated: 'Uitzondering bijgewerkt.'
      },
      errors: {
        createFailed: 'Uitzondering kan niet worden gemaakt. Probeer het opnieuw.',
        removeFailed: 'Uitzondering kan niet worden verwijderd. Probeer het opnieuw.'
      },
      pagination: {
        previous: 'Vorige',
        next: 'Volgende',
        showing: '{count} van {limit} uitzonderingen'
      }
    },
    users: {
      heading: 'Gebruikersbeheer',
      description: 'Maak, bewerk en deactiveer beheer- en moderatoraccounts.',
      search: {
        label: 'Zoek gebruikers',
        placeholder: 'Zoek op e-mail of ID',
        clear: 'Zoekopdracht wissen'
      },
      filters: {
        role: 'Rol',
        roleAll: 'Alle rollen',
        status: 'Status',
        statusAll: 'Alle statussen'
      },
      roles: {
        admin: 'Beheerder',
        moderator: 'Moderator'
      },
      status: {
        active: 'Actief',
        disabled: 'Uitgeschakeld'
      },
      actions: {
        add: 'Gebruiker toevoegen',
        edit: 'Gebruiker bewerken',
        deactivate: 'Deactiveren',
        deactivating: 'Bezig met deactiveren…',
        activate: 'Activeren',
        activating: 'Bezig met activeren…'
      },
      columns: {
        email: 'E-mail',
        roles: 'Rollen',
        status: 'Status',
        lastLogin: 'Laatste login',
        created: 'Aangemaakt',
        actions: 'Acties'
      },
      table: {
        empty: 'Geen gebruikers gevonden voor deze weergave.',
        loading: 'Gebruikers laden…',
        error: 'Gebruikers kunnen niet worden geladen.',
        retry: 'Opnieuw proberen',
        never: 'Nooit'
      },
      pagination: {
        previous: 'Vorige',
        next: 'Volgende',
        showing: '{count} van {limit} gebruikers'
      },
      dialogs: {
        actions: {
          cancel: 'Annuleren'
        },
        create: {
          title: 'Beheerder of moderator uitnodigen',
          description: 'Voer een e-mailadres in en ken minimaal één rol toe. Nieuwe gebruikers ontvangen een e-mail zodra het account klaarstaat.',
          email: 'Werk e-mailadres',
          roles: 'Rollen toewijzen',
          submit: 'Gebruiker aanmaken',
          submitting: 'Bezig met aanmaken…',
          errors: {
            email: 'Voer een e-mailadres in.',
            roles: 'Selecteer minimaal één rol.',
            generic: 'Gebruiker kan niet worden aangemaakt. Probeer het opnieuw.'
          }
        },
        edit: {
          title: '{email} bewerken',
          description: 'Werk roltoewijzingen of de accountstatus bij.',
          roles: 'Rollen',
          status: 'Accountstatus',
          submit: 'Wijzigingen opslaan',
          submitting: 'Bezig met opslaan…',
          errors: {
            roles: 'Wijs minimaal één rol toe.',
            generic: 'Gebruiker kan niet worden bijgewerkt. Probeer het opnieuw.'
          }
        }
      },
      toasts: {
        created: '{email} uitgenodigd.',
        updated: '{email} bijgewerkt.',
        deactivated: '{email} gedeactiveerd.',
        activated: '{email} geactiveerd.'
      },
      errors: {
        deactivate: 'Deactiveren is mislukt. Probeer het opnieuw.',
        activate: 'Activeren is mislukt. Probeer het opnieuw.'
      },
      confirm: {
        deactivate: '{email} deactiveren?'
      }
    },
    audit: {
      heading: 'Auditlogboek',
      description: 'Bekijk gevoelige wijzigingen binnen de beheerconsole.',
      filters: {
        actorLabel: 'Filter op e-mailadres van actor',
        actorPlaceholder: 'Filter op e-mailadres van actor',
        actionLabel: 'Filter op actienaam',
        actionPlaceholder: 'Filter op actienaam'
      },
      columns: {
        actor: 'Actor',
        action: 'Actie',
        entity: 'Entiteit',
        metadata: 'Metadata',
        timestamp: 'Tijdstip'
      },
      table: {
        empty: 'Geen auditevenementen gevonden.',
        loading: 'Auditevenementen laden…',
        error: 'Auditevenementen kunnen niet worden geladen.',
        retry: 'Opnieuw proberen'
      },
      metadata: {
        unavailable: 'Niet beschikbaar'
      },
      roles: {
        none: 'Geen rollen'
      },
      pagination: {
        previous: 'Vorige',
        next: 'Volgende',
        showing: '{count} van {limit} gebeurtenissen'
      }
    },
    activity: {
      heading: 'Activiteitenlogboek',
      description: 'Volg alle beheeracties en systeemgebeurtenissen.',
      viewMode: {
        table: 'Tabel',
        timeline: 'Tijdlijn'
      },
      export: 'Exporteer CSV',
      filters: {
        actor: 'Filter op gebruiker',
        actorPlaceholder: 'Voer e-mailadres in',
        action: 'Actietype',
        allActions: 'Alle acties',
        entity: 'Entiteitstype',
        allEntities: 'Alle entiteiten',
        dateRange: 'Tijdsperiode',
        today: 'Vandaag',
        lastWeek: 'Laatste 7 dagen',
        lastMonth: 'Laatste 30 dagen',
        allTime: 'Alle tijd',
        clear: 'Wis filters'
      },
      actions: {
        create: 'Aanmaken',
        update: 'Bijwerken',
        delete: 'Verwijderen',
        approve: 'Goedkeuren',
        reject: 'Afwijzen',
        login: 'Inloggen',
        logout: 'Afmelden'
      },
      entities: {
        video: 'Video',
        playlist: 'Afspeellijst',
        channel: 'Kanaal',
        category: 'Categorie',
        user: 'Gebruiker',
        settings: 'Instellingen'
      },
      table: {
        timestamp: 'Tijdstip',
        actor: 'Actor',
        action: 'Actie',
        entity: 'Entiteit',
        details: 'Details'
      },
      noResults: 'Geen activiteitsvermeldingen gevonden. Probeer filters aan te passen.',
      loadingMore: 'Meer vermeldingen laden...',
      loadMore: 'Meer laden',
      reachedEnd: 'Je hebt het einde van het activiteitenlogboek bereikt.',
      error: 'Activiteitenlogboek laden mislukt. Probeer het opnieuw.',
      retry: 'Opnieuw proberen'
    },
    preferences: {
      localeLabel: 'Interfacetaal',
      locales: {
        en: 'Engels',
        ar: 'Arabisch',
        nl: 'Nederlands'
      }
    },
    bulkImportExport: {
      heading: 'Bulk Import/Export',
      subtitle: 'Importeer of exporteer inhoud en categorieën in bulk met CSV-bestanden',
      export: {
        title: 'Gegevens exporteren',
        description: 'Download uw gegevens als CSV-bestanden voor back-up of analyse',
        selectType: 'Selecteer gegevenstype',
        types: {
          content: 'Alle inhoud',
          categories: 'Categorieën'
        },
        contentFilters: 'Inhoudsfilters',
        includeChannels: 'Kanalen opnemen',
        includePlaylists: 'Afspeellijsten opnemen',
        includeVideos: "Video's opnemen",
        downloadCSV: 'Download CSV',
        exporting: 'Exporteren...',
        success: 'Export succesvol voltooid!',
        error: 'Gegevens exporteren mislukt. Probeer het opnieuw.'
      },
      import: {
        title: 'Gegevens importeren',
        description: 'Upload CSV-bestanden om inhoud of categorieën in bulk toe te voegen',
        selectType: 'Selecteer importtype',
        types: {
          channels: 'Kanalen',
          categories: 'Categorieën'
        },
        chooseFile: 'Kies CSV-bestand',
        fileSize: 'Bestandsgrootte',
        uploadCSV: 'Uploaden en verwerken',
        importing: 'Importeren...',
        results: 'Importresultaten',
        totalProcessed: 'Totaal verwerkte rijen',
        successful: 'Gelukt',
        failed: 'Mislukt',
        row: 'Rij',
        status: 'Status',
        message: 'Bericht',
        statusSuccess: 'Succes',
        statusError: 'Fout',
        rowSuccess: 'Succesvol geïmporteerd',
        rowError: 'Importeren mislukt',
        importComplete: '{count} items succesvol geïmporteerd',
        error: 'Bestand importeren mislukt. Controleer het formaat.',
        errorEmptyFile: 'CSV-bestand is leeg of ongeldig'
      },
      templates: {
        title: 'CSV-sjablonen',
        description: 'Download sjabloonbestanden om het vereiste formaat voor imports te zien',
        channels: 'Download kanalensjabloon',
        categories: 'Download categorieënsjabloon'
      }
    }
  }
};
