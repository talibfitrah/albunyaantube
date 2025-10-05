# Main Branch Work - Safe During Parallel Development

While 3 engineers work on feature branches, you can safely work on these infrastructure tasks on `main`:

## ✅ Safe Tasks (Won't Conflict)

### Priority 1: CI/CD (Week 1)
- **INFRA-01**: Android CI pipeline (`.github/workflows/android-ci.yml`)
- **INFRA-02**: Frontend CI pipeline (`.github/workflows/frontend-ci.yml`)  
- **INFRA-03**: Docker setup (`docker-compose.yml`)

### Priority 2: Testing (Week 2)
- **TEST-01**: Android test utilities (`android/.../testutil/`)
- **TEST-02**: Backend integration tests (`backend/.../integration/`)

### Priority 3: Documentation (Week 3)
- **DOCS-01**: API documentation (`docs/api/openapi-draft.yaml`)
- **DOCS-02**: Architecture diagrams (`docs/architecture/diagrams/`)

### Priority 4: Quality & DX (Week 4)
- **QUALITY-01**: Code formatting (`.editorconfig`, `.ktlint`)
- **DX-01**: Dev setup scripts (`scripts/setup.sh`)
- **I18N-01**: Localization files (`values-ar/`, `values-nl/`)

## 🚫 Avoid (Will Conflict)
- ❌ `backend/.../controller/` - Backend engineer working
- ❌ `backend/.../service/` - Backend engineer working
- ❌ `frontend/src/components/admin/` - Frontend engineer working
- ❌ `android/.../ui/downloads/` - Android engineer working

## 🚀 Start Here

```bash
# Create Android CI
cat > .github/workflows/android-ci.yml << 'EOF'
name: Android CI
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    - name: Build
      run: cd android && ./gradlew assembleDebug
    - name: Test
      run: cd android && ./gradlew test
EOF

git add .github/workflows/android-ci.yml
git commit -m "INFRA-01: Add Android CI pipeline"
git push origin main
```

See full details in this file for all 15 tasks.
