# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

DocChat AI answers questions about a user's PDF documents with citations to the source pages, using RAG. It is built in phases, and only part of it exists: the "Project status" table in [README.md](README.md) is the source of truth for what is implemented and what has not been verified. The README also has the environment variable table and a troubleshooting table.

## Commands

Backend, from `backend/`. Use `./mvnw` in Bash and `.\mvnw.cmd` in PowerShell.

```bash
./mvnw test                                  # Unit and web tests. No Docker needed.
./mvnw test -Dtest=CorrelationIdFilterTest   # One class
./mvnw test -Dtest='SystemStatusControllerTest#returnsTheContractUsedByTheFrontend'   # One method
./mvnw verify                                # Also runs *IT classes. Fails without Docker.
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # Needs the db service and ../.env
```

Windows PowerShell splits unquoted arguments at the dot, so quote dotted properties: `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'`.

Frontend, from `frontend/`:

```bash
npm ci
npm start                                    # ng serve on port 4200, proxying the API to port 8080
npm test -- --watch=false
npm test -- --watch=false --include='src/app/features/home/home.spec.ts'   # One file
npm test -- --watch=false --filter='recovers'                              # By test name
npm run lint
npm run build
```

Whole stack, from the repository root. Compose refuses to start until `POSTGRES_PASSWORD` is set in `.env`.

```bash
cp .env.example .env
docker compose up --build    # Everything, at http://localhost:4200
docker compose up -d db      # Only the database, for local development
```

## Architecture

**Modular monolith.** One Spring Boot application, packaged by business module under `com.docchat.<module>` ([ADR 0001](docs/adr/0001-modular-monolith.md)). Nothing enforces the boundaries except discipline. A package is created only when it has real code, so do not scaffold the planned modules (`auth`, `document`, `ingestion`, `rag`, `chat`, `ai`, `storage`) ahead of their phase. The Spring AI BOM is imported, but its starters are deliberately not added until Phase 3 ([ADR 0002](docs/adr/0002-spring-boot-4-and-spring-ai-2.md)).

**Single origin.** The browser only talks to the Angular host, so the backend has no CORS configuration and frontend code calls relative URLs such as `/api/system/status`. `/api`, `/v3/api-docs` and `/swagger-ui` are forwarded by `proxy.conf.json` under `ng serve` and by `nginx.conf` in Docker. `/actuator` is not forwarded, so reach it on the backend port. `nginx.conf` deliberately sets `proxy_pass` through a variable, which makes nginx re-resolve the name and keeps the proxy working when the backend container is recreated with a new IP. Do not simplify it to a literal URL.

**Spring profiles.** `application.yml` has no datasource on purpose, so starting without a profile fails instead of connecting to an unintended database.
- `dev`: backend on the host, database in Compose. It reads `../.env` relative to the working directory, so start the backend from `backend/`.
- `prod`: every value comes from the `DOCCHAT_DB_*` variables, and logs are structured (ECS). Compose uses this profile.

**Errors and correlation ids.** `CorrelationIdFilter` assigns an id to each request and puts it in the `X-Correlation-Id` response header and the logging MDC. `GlobalExceptionHandler` renders errors as RFC 9457 Problem Details. Its `createResponseEntity` override adds `code`, `timestamp` and `correlationId`, so a new `@ExceptionHandler` must return through `handleExceptionInternal` or those members are lost. Unexpected exceptions are logged and answered with a generic message, so internal details never reach the client.

**Status versus health.** `GET /api/system/status` always answers 200 with the real state in its body, and the UI displays it. `/actuator/health/readiness` answers 503 when the database or pgvector is down. Liveness does not depend on the database. Both status and the `pgvector` health indicator read from `SystemStatusService`. A new hard dependency must be added to the readiness group in `application.yml`, because a health indicator alone does not affect readiness.

**Database.** Schema changes go through Flyway migrations in `backend/src/main/resources/db/migration`. Relational data and embeddings share one PostgreSQL database ([ADR 0003](docs/adr/0003-pgvector-for-vector-storage.md)). The vector dimension depends on the embedding model and is defined in Phase 3.

**Spring Boot 4.** The project uses modular starters, including one test starter per technology, and new package names such as `org.springframework.boot.health.contributor` and `org.springframework.boot.webmvc.test.autoconfigure`. Code from older tutorials usually needs adapting.

**Frontend.** Angular standalone components with signals, `OnPush` and `inject()`. Files and classes have no `.component` suffix (`home.ts`, `Home`). `core/<domain>` holds services and models, and `features/<name>` holds lazily loaded pages (`loadComponent` in `app.routes.ts`).

## Tests

- Surefire runs `*Test` classes and Failsafe runs `*IT` classes, so anything that needs Docker must be named `*IT`.
- Tests run with no Spring profile, so a `@SpringBootTest` must bring its own database. Use `@Import(TestcontainersConfiguration.class)` as `FoundationIT` does, or the unreachable-database properties in `ApplicationWithoutDatabaseTest`, which proves the context loads without Docker.
- Frontend specs run on Vitest with jsdom. They find elements through `data-testid` and fake the backend with `HttpTestingController`.
- No test may call a paid API.
- Mockito prints a self-attach warning during backend tests. It is harmless.

## Keep in sync

- The pgvector image in `compose.yaml` and `POSTGRES_IMAGE` in `TestcontainersConfiguration`.
- Forwarded paths in `frontend/proxy.conf.json` and `frontend/nginx.conf`.
- Backend response records and the TypeScript models in `frontend/src/app/core`. They are mirrored by hand, with no code generation.

## Working rules

- Code, documentation and UI text are in English.
- Consult the user before important decisions: base versions, deviations from the phase plan, machine installs, anything with a cost, and the choices still open (authentication strategy and UI component library before Phase 2, AI provider before Phase 3). Significant decisions are recorded as ADRs in `docs/adr/`.
- Never report something as verified unless it was executed. If a check could not be run, say so and record it in the README's "Project status" section.
- Make one local commit at the end of each phase. Push only when explicitly asked.
