# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

DocChat AI answers questions about a user's PDF documents with citations to the source pages, using RAG. It is built in phases, and only part of it exists: the "Project status" table in [README.md](README.md) is the source of truth for what is implemented and how it was verified. The README also has the API, environment variable and troubleshooting tables.

## Commands

Backend, from `backend/`. Use `./mvnw` in Bash and `.\mvnw.cmd` in PowerShell.

```bash
./mvnw test                                  # Unit and web tests. No Docker needed.
./mvnw test -Dtest=CorrelationIdFilterTest   # One class
./mvnw test -Dtest='SystemStatusControllerTest#returnsTheContractUsedByTheFrontend'   # One method
./mvnw verify                                # Also runs *IT classes. Fails without Docker.
./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='AuthFlowIT'   # One IT class
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
docker compose up -d db ollama ollama-models   # Only what local development needs
docker compose down          # Stops everything and keeps the data
docker compose down -v       # Also DELETES the database, the uploaded files and the ~8 GB of models
```

## Architecture

**Modular monolith.** One Spring Boot application, packaged by business module under `com.docchat.<module>` ([ADR 0001](docs/adr/0001-modular-monolith.md)). Nothing enforces the boundaries except discipline. A package is created only when it has real code. Persistence uses `JdbcClient` with explicit SQL, not JPA.

**Single origin.** The browser only talks to the Angular host, so the backend has no CORS configuration and frontend code calls relative URLs such as `/api/system/status`. `/api`, `/v3/api-docs` and `/swagger-ui` are forwarded by `proxy.conf.json` under `ng serve` and by `nginx.conf` in Docker. `/actuator` is not forwarded, so reach it on the backend port. `nginx.conf` deliberately sets `proxy_pass` through a variable, which makes nginx re-resolve the name and keeps the proxy working when the backend container is recreated with a new IP. Do not simplify it to a literal URL.

**Spring profiles.** `application.yml` has no datasource on purpose, so starting without a profile fails instead of connecting to an unintended database.
- `dev`: backend on the host, database and Ollama in Compose. It reads `../.env` relative to the working directory, so start the backend from `backend/`.
- `prod`: every value comes from the `DOCCHAT_DB_*` variables, and logs are structured JSON. Compose uses this profile.

**Authentication.** Server-side sessions in PostgreSQL behind an HttpOnly cookie ([ADR 0004](docs/adr/0004-server-side-sessions.md)), configured in `SecurityConfig`. Login and logout are Spring Security filters, not controller methods, and login takes a form post. Controllers get the user through `@AuthenticationPrincipal AccountPrincipal`. Every route is authenticated unless `SecurityConfig` lists it. Angular's HttpClient sends the CSRF token by itself from the `XSRF-TOKEN` cookie, which any backend response sets, and `AuthService` refreshes after sign-out because sign-out removes that cookie.

**Errors and correlation ids.** `CorrelationIdFilter` assigns an id to each request and puts it in the `X-Correlation-Id` response header and the logging MDC. `GlobalExceptionHandler` renders errors as RFC 9457 Problem Details. Its `createResponseEntity` override adds `code`, `timestamp` and `correlationId`, so a new `@ExceptionHandler` must return through `handleExceptionInternal` or those members are lost. Throw `ApiException` for expected failures; its message reaches the client. Security failures in the filter chain are routed to the same handler through `HandlerExceptionResolver`. Unexpected exceptions are logged and answered with a generic message, so internal details never reach the client.

**Documents.** `DocumentService` stores the file, inserts a `PROCESSING` row and publishes `DocumentUploadedEvent`. `ingestion.DocumentProcessor` listens for it, so `document` does not depend on `ingestion`, and extracts the text on a bounded executor. `DocumentRepository.completeProcessing` updates the row before inserting pages, because that lock is what makes a concurrent delete safe. Rows still `PROCESSING` at startup are processed again. Every user-facing query filters by owner, and another user's document answers 404. Files are on the local disk ([ADR 0005](docs/adr/0005-local-file-storage.md)), with no abstraction over the storage on purpose.

**RAG.** Local models through Ollama in Compose ([ADR 0007](docs/adr/0007-local-ai-with-ollama.md)). Spring AI is only the client: the `ai` module wraps `EmbeddingModel` and `ChatModel` and turns failures into `AiUnavailableException`. Its pgvector store is deliberately not used, because `document_chunks` keeps foreign keys to pages and documents, and `rag.ChunkSearchRepository` searches with SQL that always filters by owner. Passages never cross a page. `rag.AnswerService` sends numbered passages to the model, which never sees file names or page numbers, and `AnswerParser` keeps only citations of passages it was given. A reply with no valid citation counts as not found, so every answer shown traces back to a page. The wording in `AnswerPrompt` and `RewritePrompt` was chosen by testing variants against the real model, and small changes had surprising effects, so re-test with real models before changing them. Judge a rewriting prompt by the final answer, with real search: a wording that looked right on its own made answers worse.

**Conversations.** `chat.ConversationService` asks `rag.AnswerService` and then stores the question and answer together, outside any transaction during the model's work, so a failed question leaves nothing ([ADR 0008](docs/adr/0008-conversations-and-follow-up-questions.md)). With earlier turns, `rag.QuestionRewriter` first makes the question stand alone, and the result is stored as `interpretedQuestion` and shown as "Searched for". A citation points at its passage through a foreign key instead of copying the text, so deleting a document clears `document_id` and removes the excerpt while the answer stays. Anything stored from a request or the model goes through `PgText.clean`.

**Status versus health.** `GET /api/system/status` always answers 200 with the real state in its body, and the UI displays it. `/actuator/health/readiness` answers 503 when the database, pgvector or the storage is down, and deliberately ignores the AI service, because accounts and documents work without it. Liveness does not depend on any of them. A new hard dependency must be added to the readiness group in `application.yml`, because a health indicator alone does not affect readiness.

**Database.** Schema changes go through Flyway migrations in `backend/src/main/resources/db/migration`. Relational data, sessions and embeddings share one PostgreSQL database ([ADR 0003](docs/adr/0003-pgvector-for-vector-storage.md)). The vector column has dimension 1024 for `bge-m3`, so an embedding model of another dimension needs a new migration and reprocessing. Text must not contain NUL characters or unpaired surrogates, which `PgText.clean` removes.

**Spring Boot 4.** The project uses modular starters, including one test starter per technology, and new package names such as `org.springframework.boot.health.contributor` and `org.springframework.boot.webmvc.test.autoconfigure`. It also uses Jackson 3 and Spring Security 7. Code from older tutorials usually needs adapting.

**Frontend.** Angular standalone components with signals, `OnPush` and `inject()`, using Angular Material ([ADR 0006](docs/adr/0006-angular-material.md)) with no icon fonts or other external assets. Files and classes have no `.component` suffix (`home.ts`, `Home`). `core/<domain>` holds services and models, and `features/<name>` holds lazily loaded pages (`loadComponent` in `app.routes.ts`). `/ask` and `/ask/:id` share one route through the `askUrl` matcher, so moving between conversations keeps the same page instead of recreating it. Interpolation adds spaces around inline text, so text next to citations and marked passages is bound with `[textContent]`. An app initializer asks who is signed in before routing starts, so guards can read `AuthService` synchronously.

## Tests

- Surefire runs `*Test` classes and Failsafe runs `*IT` classes, so anything that needs Docker must be named `*IT`.
- Tests run with no Spring profile, so a `@SpringBootTest` must bring its own database. Use `@Import(TestcontainersConfiguration.class)`, or the unreachable-database properties in `ApplicationWithoutDatabaseTest`, which proves the context loads without Docker. `src/test/resources/application-default.yml` applies to all of them.
- Tests never reach a real model: `application-default.yml` points Ollama at an unreachable address, and `testsupport.FakeAiConfiguration` provides predictable fakes. Integration tests import it with `TestcontainersConfiguration`. These fakes prove the plumbing, not the quality of answers, so changes to prompts, models or retrieval must also be run against the real models. `FakeChatModel` repeats the question when asked to rewrite it, unless `setRewriteReply` says otherwise, and `setDuringAnswer` runs something while the model works, such as a delete.
- `AuthFlowIT`, `DocumentFlowIT`, `QuestionFlowIT` and `ConversationFlowIT` use real HTTP through `testsupport.ApiClient`, which acts like the browser with cookies and CSRF. `testsupport.TestPdfs` generates real PDF files.
- A `@WebMvcTest` must `@Import(SecurityConfig.class)` to get the real rules. Requests then need `.with(user(new AccountPrincipal(...)))` and, if they change data, `.with(csrf())`.
- Frontend specs run on Vitest with jsdom. They find elements through `data-testid` and fake the backend with `HttpTestingController`. `ask.spec.ts` uses `RouterTestingHarness` with the real `askUrl` matcher, because the page depends on route reuse. That fake does not exercise what a browser really sends, so check request bodies and headers explicitly, and run authentication changes in a real browser. A `URLSearchParams` login body passed every spec while the browser sent it as JSON.
- No test may call a paid API.
- Mockito's self-attach warning and PDFBox's fallback font warning are harmless.

## Keep in sync

- The pgvector image in `compose.yaml` and `POSTGRES_IMAGE` in `TestcontainersConfiguration`.
- Forwarded paths in `frontend/proxy.conf.json` and `frontend/nginx.conf`.
- The upload limit in `spring.servlet.multipart` in `application.yml`, `client_max_body_size` in `frontend/nginx.conf` and `MAX_FILE_SIZE_BYTES` in `document.model.ts`.
- `spring.http.clients.read-timeout` in `application.yml` must stay below `proxy_read_timeout` in `frontend/nginx.conf`.
- The model names in `application.yml` and `compose.yaml`, and the vector dimension in the migrations with the embedding model.
- The question length in `QuestionRequest` in `ConversationController` and `MAX_QUESTION_LENGTH` in `ask.ts`.
- Backend response records and the TypeScript models in `frontend/src/app/core`, mirrored by hand with no code generation. `DocumentFailureReason` also needs a message in `FAILURE_MESSAGES` in `documents.ts`.

## Working rules

- The project stays on the user's machine. Do not add AWS or any other cloud service, deployment or hosted CI.
- Use only what is free. Do not try, propose or configure anything paid.
- Commits stay local. Do not add a remote or push. Make one commit at the end of each phase, on a branch for that phase.
- Code, documentation and UI text are in English.
- Consult the user before important decisions: base versions, deviations from the phase plan, machine installs, and anything else with a lasting effect. Significant decisions are recorded as ADRs in `docs/adr/`.
- Never report something as verified unless it was executed. If a check could not be run, say so and record it in the README's "Project status" section.
