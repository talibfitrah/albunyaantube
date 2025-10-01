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
      exclusions: 'Exclusions',
      users: 'Users',
      audit: 'Audit log',
      home: 'Home',
      channels: 'Channels',
      playlists: 'Playlists',
      videos: 'Videos'
    },
    layout: {
      skipToContent: 'Skip to main content'
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
    preferences: {
      localeLabel: 'Interface language',
      locales: {
        en: 'English',
        ar: 'العربية',
        nl: 'Nederlands'
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
      registry: 'السجل',
      moderation: 'الإشراف',
      exclusions: 'الاستثناءات',
      users: 'المستخدمون',
      audit: 'سجل التدقيق',
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
    preferences: {
      localeLabel: 'لغة الواجهة',
      locales: {
        en: 'الإنجليزية',
        ar: 'العربية',
        nl: 'الهولندية'
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
      registry: 'Registerbeheer',
      moderation: 'Moderatie',
      exclusions: 'Uitzonderingen',
      users: 'Gebruikers',
      audit: 'Auditlogboek',
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
    preferences: {
      localeLabel: 'Interfacetaal',
      locales: {
        en: 'Engels',
        ar: 'Arabisch',
        nl: 'Nederlands'
      }
    }
  }
};
