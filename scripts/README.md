# Development Scripts

Automation scripts for AlBunyaan Tube development workflow.

## Available Scripts

### `setup-dev.sh` - First-Time Setup

Automates initial development environment setup for new developers.

**Usage:**
```bash
./scripts/setup-dev.sh
```

**What it does:**
1. ✅ Checks all prerequisites (Java 17, Node.js 18+, Git, Docker)
2. 📝 Creates `.env` file from template
3. ☕ Downloads and builds backend (Gradle)
4. ⚛️ Installs and builds frontend (npm)
5. 🤖 Sets up Android environment (if ANDROID_HOME set)
6. 🔥 Prepares Firebase emulator data directory

**Output:**
- Colored terminal output (✓ success, ✗ error, ℹ info)
- Summary of next steps (Docker or manual startup)
- Links to documentation

**Estimated time:** 5-10 minutes (depending on internet speed)

---

### `validate-env.sh` - Environment Health Check

Validates that all required tools and configurations are properly set up.

**Usage:**
```bash
./scripts/validate-env.sh
```

**What it checks:**

**Java Environment:**
- Java 17 installed and configured
- JAVA_HOME environment variable

**Node.js Environment:**
- Node.js 18+ installed
- npm available

**Version Control:**
- Git installed
- Git user configured

**Docker (optional):**
- Docker installed
- Docker daemon running
- Docker Compose available

**Project Structure:**
- All required directories present
- Configuration files exist
- Gradle wrappers executable
- Frontend dependencies installed

**Exit codes:**
- `0`: All checks passed (or only warnings)
- `1`: Critical errors found

---

## Workflow

### For New Developers

```bash
# 1. Clone repository
git clone https://github.com/your-org/albunyaantube.git
cd albunyaantube

# 2. Run setup script
./scripts/setup-dev.sh

# 3. Edit .env file
nano .env  # Add your YOUTUBE_API_KEY

# 4. Validate environment
./scripts/validate-env.sh

# 5. Start development
docker-compose up -d
```

### For Existing Developers

```bash
# Check environment health
./scripts/validate-env.sh

# If issues found, re-run setup
./scripts/setup-dev.sh
```

---

## Related Documentation

- [Docker Setup](../docs/DOCKER_SETUP.md) - Running with Docker Compose
- [Platform Guides](../docs/PLATFORM_GUIDES.md) - Platform-specific instructions
- [Quick Start](../docs/QUICK_START_PARALLEL_WORK.md) - Parallel development workflow
