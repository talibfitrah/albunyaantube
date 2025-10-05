# Frontend Deployment Checklist

**Version**: Sprint 2 Release
**Date**: 2025-10-05
**Merge Commit**: a94b22b

---

## Pre-Deployment Checklist

### Code Quality
- [x] All tests passing (29 existing + 58 new)
- [x] Build succeeds without errors
- [x] TypeScript compilation successful
- [x] ESLint checks pass
- [x] No console errors in development
- [x] Code reviewed
- [x] Merged to main branch

### Performance
- [x] Bundle size optimized (70 KB main bundle)
- [x] Code splitting implemented
- [x] Lazy loading active
- [x] Images optimized
- [x] Performance monitoring configured

### Security
- [x] Environment variables properly configured
- [x] API keys not exposed in code
- [x] Authentication working
- [x] Authorization checks in place
- [x] CORS configured on backend

### Documentation
- [x] README updated
- [x] API integration documented
- [x] Performance guide created
- [x] Deployment guide available
- [x] Environment variables documented

---

## Environment Configuration

### Required Environment Variables

#### Development
```bash
VITE_API_BASE_URL=http://localhost:8080
VITE_FIREBASE_API_KEY=your_dev_key
VITE_FIREBASE_AUTH_DOMAIN=your-dev-project.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=your-dev-project
VITE_FIREBASE_STORAGE_BUCKET=your-dev-project.appspot.com
VITE_FIREBASE_MESSAGING_SENDER_ID=your_sender_id
VITE_FIREBASE_APP_ID=your_app_id
```

#### Staging
```bash
VITE_API_BASE_URL=https://api-staging.albunyaan.com
VITE_FIREBASE_API_KEY=your_staging_key
# ... (staging Firebase config)
```

#### Production
```bash
VITE_API_BASE_URL=https://api.albunyaan.com
VITE_FIREBASE_API_KEY=your_production_key
# ... (production Firebase config)
```

---

## Build Process

### Local Build Test
```bash
cd frontend
npm install
npm run build
npm run preview  # Test production build locally
```

### Expected Build Output
```
✓ 131 modules transformed
dist/index.html                     1.42 kB
dist/assets/index-*.css            15.63 kB
dist/assets/index-*.js             70.09 kB (gzipped: 21.81 kB)
dist/assets/vue-core-*.js         127.54 kB (gzipped: 46.18 kB)
dist/assets/firebase-*.js         158.93 kB (gzipped: 32.74 kB)
+ per-view chunks (2-15 KB each)
```

### Build Time
- Development: ~2-3 seconds
- Production: ~5-8 seconds

---

## Deployment Steps

### 1. Pre-Deployment Verification
```bash
# Ensure on main branch
git checkout main
git pull origin main

# Verify latest commit
git log -1

# Check for uncommitted changes
git status
```

### 2. Build for Production
```bash
cd frontend

# Install dependencies (if not already)
npm install

# Run tests
npm test

# Build for production
npm run build

# Verify build output
ls -lh dist/
```

### 3. Deploy to Hosting

#### Option A: Firebase Hosting
```bash
# Login to Firebase
firebase login

# Deploy to staging first
firebase deploy --only hosting:staging

# Test staging: https://staging.albunyaan.com

# Deploy to production
firebase deploy --only hosting:production
```

#### Option B: Static Hosting (Netlify/Vercel)
```bash
# Deploy via CLI or drag-drop dist/ folder
netlify deploy --prod --dir=frontend/dist
# or
vercel --prod frontend/dist
```

#### Option C: Custom Server
```bash
# Copy dist/ to server
scp -r frontend/dist/* user@server:/var/www/albunyaan/

# Update nginx/apache config
# Restart web server
sudo systemctl restart nginx
```

### 4. Post-Deployment Verification

#### Smoke Tests
- [ ] Homepage loads
- [ ] Login works
- [ ] Dashboard loads
- [ ] Content search works
- [ ] Categories load
- [ ] Approvals queue loads
- [ ] No console errors
- [ ] API calls succeed

#### Performance Check
- [ ] Lighthouse score >90
- [ ] LCP <2.5s
- [ ] FID <100ms
- [ ] CLS <0.1
- [ ] Bundle loads quickly

#### Browser Testing
- [ ] Chrome (latest)
- [ ] Firefox (latest)
- [ ] Safari (latest)
- [ ] Edge (latest)
- [ ] Mobile browsers

---

## Rollback Plan

### If Issues Detected

#### Quick Rollback (Firebase)
```bash
# List deployments
firebase hosting:clone

# Rollback to previous version
firebase hosting:clone source-site:version target-site
```

#### Manual Rollback
```bash
# Checkout previous stable commit
git checkout <previous-commit>

# Rebuild and redeploy
cd frontend
npm run build
firebase deploy --only hosting:production
```

#### Hotfix Process
```bash
# Create hotfix branch from main
git checkout -b hotfix/issue-name main

# Make fix
# ... edit files ...

# Test locally
npm run build
npm run preview

# Commit and merge
git add .
git commit -m "HOTFIX: Description"
git push origin hotfix/issue-name

# Merge to main
git checkout main
git merge hotfix/issue-name
git push origin main

# Redeploy
npm run build
firebase deploy --only hosting:production
```

---

## Monitoring Setup

### Performance Monitoring
```javascript
// Already configured in src/utils/performance.ts
// Monitor LCP, FID, CLS automatically

// Check metrics in browser console
perfMonitor.getSummary();
```

### Error Tracking
```bash
# Setup Sentry (recommended)
npm install @sentry/vue

# Configure in main.ts
import * as Sentry from "@sentry/vue";

Sentry.init({
  app,
  dsn: "your-sentry-dsn",
  environment: import.meta.env.MODE,
  tracesSampleRate: 1.0,
});
```

### Analytics
```bash
# Setup Google Analytics or Plausible
# Add tracking code to index.html or main.ts
```

---

## Post-Deployment Tasks

### Immediate (Day 1)
- [ ] Monitor error rates
- [ ] Check performance metrics
- [ ] Verify all API endpoints responding
- [ ] Monitor user feedback
- [ ] Check server logs

### Short-term (Week 1)
- [ ] Review performance dashboards
- [ ] Analyze user behavior
- [ ] Gather QA feedback
- [ ] Document any issues
- [ ] Plan hotfixes if needed

### Long-term (Month 1)
- [ ] Performance optimization review
- [ ] User feedback analysis
- [ ] Feature usage metrics
- [ ] Plan next sprint improvements

---

## Success Criteria

### Technical Metrics
- ✅ Uptime: >99.9%
- ✅ Error rate: <0.1%
- ✅ Page load time: <2s
- ✅ API response time: <500ms
- ✅ Lighthouse score: >90

### User Metrics
- ✅ Login success rate: >95%
- ✅ Task completion rate: >90%
- ✅ User satisfaction: >4/5

---

## Support Contacts

### Technical Issues
- **Frontend**: Claude (via documentation)
- **Backend**: Backend team
- **Infrastructure**: DevOps team

### Escalation
1. Check documentation
2. Review error logs
3. Consult team
4. Create incident ticket

---

## Appendix

### Useful Commands

```bash
# Check current deployment
firebase hosting:channel:list

# View deployment history
firebase hosting:clone

# Local development
npm run dev

# Production build
npm run build

# Preview production build
npm run preview

# Run tests
npm test

# Type check
vue-tsc --noEmit
```

### Common Issues & Solutions

**Issue**: Build fails
**Solution**:
```bash
rm -rf node_modules
npm install
npm run build
```

**Issue**: API calls fail
**Solution**: Check VITE_API_BASE_URL environment variable

**Issue**: Authentication fails
**Solution**: Verify Firebase configuration

**Issue**: Slow page load
**Solution**: Check bundle size, verify lazy loading

---

## Sign-off

- [ ] Frontend Engineer: Verified build and tests
- [ ] QA Team: Tested in staging
- [ ] DevOps: Reviewed deployment plan
- [ ] Product Owner: Approved for production

**Deployment Authorized By**: _________________
**Date**: _________________

---

**Last Updated**: 2025-10-05
**Version**: Sprint 2 Release
**Merge Commit**: a94b22b
