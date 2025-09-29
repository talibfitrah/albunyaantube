# Local Infrastructure with Docker Compose

This repository ships with a `docker-compose.yml` file that provisions the backing services required by the Spring Boot
application during Phase 1: PostgreSQL and Redis. The compose stack mirrors the defaults defined in
`backend/src/main/resources/application.yml` so the application can start without additional configuration.

## Prerequisites
- Docker Engine 24+
- Docker Compose plugin (v2.20 or newer)

## Usage

1. Start the services:
   ```bash
   docker compose up -d
   ```

2. Verify the containers are healthy:
   ```bash
   docker compose ps
   ```

   You should see `albunyaantube-postgres` and `albunyaantube-redis` in a `healthy` state.

3. Apply database migrations and run the backend locally:
   ```bash
   cd backend
   ./gradlew bootRun
   ```

4. Stop the services when finished:
   ```bash
   docker compose down
   ```

Data is persisted in Docker named volumes (`postgres-data`, `redis-data`). Run `docker compose down -v` if you need to reset
the databases.
