# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

DocChat AI answers questions about a user's PDF documents with citations to the source pages, using RAG. It was built in phases: the "Project status" table in [README.md](README.md) is the source of truth for what is implemented and how it was verified, and "Roadmap" lists what is not. The README also has the API, environment variable, metrics and troubleshooting tables.

## Commands

Development needs JDK 21 or newer and Node.js 26.

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

Browser tests, from `e2e/`. They need Docker and Edge, and start and delete their own stack, which can run next to the main one.

```bash
npm ci
npm test                                     # Everything, with a fake model. A few minutes.
npm test -- tests/journey.spec.ts            # One file
npm test -- -g 'clicking twice'              # By test name
E2E_KEEP_STACK=1 npm test                    # Leaves the stack up to inspect after a failure. The next run replaces it.
npm run test:real                            # Real models, through the main stack's ollama service
npm run typecheck
npm run load                                 # Load test with k6 and a fake model, about 5 minutes
npm run load:real                            # The time of real answers, one at a time
E2E_SCALE_DOCUMENTS=25 npm run load:scale    # The time of searching an account full of documents
```

Whole stack, from the repository root. Compose refuses to start until `POSTGRES_PASSWORD` is set in `.env`.

```bash
cp .env.example .env
docker compose up --build    # Everything, at http://localhost:4200
docker compose up -d db ollama ollama-models   # Only what local development needs
docker compose --profile observability up -d   # Everything, plus Prometheus and Grafana at http://localhost:3000
docker compose down          # Stops everything and keeps the data
docker compose down -v       # Also DELETES the database, the uploaded files and the ~8 GB of models
```

## Architecture

**Modular monolith.** One Spring Boot application, packaged by business module under `com.docchat.<module>` ([ADR 0001](docs/adr/0001-modular-monolith.md)). Nothing enforces the boundaries except discipline. A package is created only when it has real code. Persistence uses `JdbcClient` with explicit SQL, not JPA.

**Single origin.** The browser only talks to the Angular host, so the backend has no CORS configuration and frontend code calls relative URLs such as `/api/system/status`. `/api`, `/v3/api-docs` and `/swagger-ui` are forwarded by `proxy.conf.json` under `ng serve` and by `nginx.conf` in Docker. `/actuator` is not forwarded, so reach it on the backend port. `nginx.conf` deliberately sets `proxy_pass` through a variable, which makes nginx re-resolve the name and keeps the proxy working when the backend container is recreated with a new IP. Do not simplify it to a literal URL. It also sends the Content Security Policy of the pages, which is why `inlineCritical` is off in `angular.json`: the inlined stylesheet loads through an inline handler that the policy blocks, and the application would have no styles.

**Spring profiles.** `application.yml` has no datasource on purpose, so starting without a profile fails instead of connecting to an unintended database.
- `dev`: backend on the host, database and Ollama in Compose. It reads `../.env` relative to the working directory, so start the backend from `backend/`.
- `prod`: every value comes from the `DOCCHAT_DB_*` variables, and logs are structured JSON. Compose uses this profile.

**Authentication.** Server-side sessions in PostgreSQL behind an HttpOnly cookie ([ADR 0004](docs/adr/0004-server-side-sessions.md)), configured in `SecurityConfig`. Login and logout are Spring Security filters, not controller methods, and login takes a form post. Controllers get the user through `@AuthenticationPrincipal AccountPrincipal`. Every route is authenticated unless `SecurityConfig` lists it. Angular's HttpClient sends the CSRF token by itself from the `XSRF-TOKEN` cookie, which any backend response sets, and `AuthService` refreshes after sign-out because sign-out removes that cookie.

**Errors and correlation ids.** `CorrelationIdFilter` assigns an id to each request and puts it in the `X-Correlation-Id` response header and the logging MDC. Work on other threads keeps it through `KeepLoggingContext`, so a new executor or thread needs that decorator or its logs lose the id. `GlobalExceptionHandler` renders errors as RFC 9457 Problem Details. Its `createResponseEntity` override adds `code`, `timestamp` and `correlationId`, so a new `@ExceptionHandler` must return through `handleExceptionInternal` or those members are lost. Throw `ApiException` for expected failures; its message reaches the user, because the frontend's `errorMessage` shows `detail` as written. Security failures in the filter chain are routed to the same handler through `HandlerExceptionResolver`. Unexpected exceptions are logged and answered with a generic message, so internal details never reach the client.

**Sessions during an outage.** Sessions live in the database, and the framework reads them on the way out of every request with a cookie, even if the route never asks. So `auth.SessionConfig` makes the status, health, info and metrics routes ignore the cookie, and `common.web.DatabaseUnavailableFilter`, placed before the session filter, renders what fails there as 503 `DATABASE_UNAVAILABLE`. A new route that must work without the database has to be added to `SessionConfig`.

**Documents.** `DocumentService` stores the file, inserts a `PROCESSING` row and publishes `DocumentUploadedEvent`. `ingestion.DocumentProcessor` listens for it, so `document` does not depend on `ingestion`, and extracts the text on a bounded executor. `DocumentRepository.completeProcessing` updates the row before inserting pages, because that lock is what makes a concurrent delete safe. A worker runs each document on its own daemon thread and waits only up to the time limit, because a file can keep PDFBox busy in a way that an interruption does not stop. A document stays `PROCESSING` when the application is stopping or the database is unreachable, since neither says anything about the file, and `resumeInterruptedDocuments`, at startup and every minute, queues the rows no worker has. `DocumentFailureReason.isTemporary` decides what `POST /{id}/retry` accepts. Every user-facing query filters by owner, and another user's document answers 404. Files are on the local disk ([ADR 0005](docs/adr/0005-local-file-storage.md)), with no abstraction over the storage on purpose.

**RAG.** Local models through Ollama in Compose ([ADR 0007](docs/adr/0007-local-ai-with-ollama.md)). Spring AI is only the client: the `ai` module wraps `EmbeddingModel` and `ChatModel` and turns failures into `AiUnavailableException`. Its pgvector store is deliberately not used, because `document_chunks` keeps foreign keys to pages and documents, and `rag.ChunkSearchRepository` searches with SQL that always filters by owner. Passages never cross a page. `rag.AnswerService` drops passages below `docchat.rag.min-similarity`, and if none remains it answers "not found" without asking the model. Otherwise it sends them, numbered, to the model, which never sees file names or page numbers, and `AnswerParser` keeps only citations of passages it was given. A reply with no valid citation counts as not found, so every answer shown traces back to a page. `AnswerService` runs each question on `questionExecutor`, which has no queue and so refuses extra questions with 429, and it waits only up to `question-time-limit`, which covers all the calls to the models of one question. The wording in `AnswerPrompt` and `RewritePrompt` was chosen by testing variants against the real model, and small changes had surprising effects, so re-test with real models before changing them. Judge a rewriting prompt by the final answer, with real search: a wording that looked right on its own made answers worse.

**Conversations.** `chat.ConversationService` asks `rag.AnswerService` and then stores the question and answer together, outside any transaction during the model's work, so a failed question leaves nothing ([ADR 0008](docs/adr/0008-conversations-and-follow-up-questions.md)). With earlier turns, `rag.QuestionRewriter` first makes the question stand alone, and the result is stored as `interpretedQuestion` and shown as "Searched for". A citation points at its passage through a foreign key instead of copying the text, so deleting a document clears `document_id` and removes the excerpt while the answer stays. Anything stored from a request or the model goes through `PgText.clean`.

**Observability.** Stages are measured with Micrometer's Observation API, in `AnswerService` and `DocumentProcessor`, and the `stage` method in each is the way to add one ([ADR 0011](docs/adr/0011-observability-and-load-tests.md)). Tags must hold only fixed words, never ids, names or text, and they must be the same on every use of a metric, or Prometheus drops the meter, which is why `outcome` starts with a default. `/actuator/prometheus` is public on the backend's port and not forwarded by the frontend. Prometheus and Grafana are in `observability/compose.yaml` under a profile, included by both Compose files. Spring Boot turns off exporting metrics in tests, so `application-default.yml` turns it back on.

**Status versus health.** `GET /api/system/status` always answers 200 with the real state in its body, and the UI displays it. `/actuator/health/readiness` answers 503 when the database, pgvector or the storage is down, and deliberately ignores the AI service, because accounts and documents work without it. Liveness does not depend on any of them. A new hard dependency must be added to the readiness group in `application.yml`, because a health indicator alone does not affect readiness.

**Database.** Schema changes go through Flyway migrations in `backend/src/main/resources/db/migration`. Relational data, sessions and embeddings share one PostgreSQL database ([ADR 0003](docs/adr/0003-pgvector-for-vector-storage.md)). The vector column has dimension 1024 for `bge-m3`, so an embedding model of another dimension needs a new migration and reprocessing. Text must not contain NUL characters or unpaired surrogates, which `PgText.clean` removes.

**Spring Boot 4.** The project uses modular starters, including one test starter per technology, and new package names such as `org.springframework.boot.health.contributor` and `org.springframework.boot.webmvc.test.autoconfigure`. It also uses Jackson 3 and Spring Security 7. Code from older tutorials usually needs adapting.

**Frontend.** Angular standalone components with signals, `OnPush` and `inject()`, using Angular Material ([ADR 0006](docs/adr/0006-angular-material.md)) with no icon fonts or other external assets. Colours, fonts, radii and shadows are CSS variables in `styles.scss`, which also feed Material's theme, so use them instead of literal values and keep text pairs at 4.5:1 contrast or more. Icons are `core/ui/icon.ts`, inline SVG with no text of their own. Files and classes have no `.component` suffix (`home.ts`, `Home`). `core/<domain>` holds services and models, and `features/<name>` holds lazily loaded pages (`loadComponent` in `app.routes.ts`). `/ask` and `/ask/:id` share one route through the `askUrl` matcher, so moving between conversations keeps the same page instead of recreating it. Interpolation adds spaces around inline text, so text next to citations and marked passages is bound with `[textContent]`. An app initializer asks who is signed in before routing starts, so guards can read `AuthService` synchronously.

## Tests

- Surefire runs `*Test` classes and Failsafe runs `*IT` classes, so anything that needs Docker must be named `*IT`.
- Tests run with no Spring profile, so a `@SpringBootTest` must bring its own database. Use `@Import(TestcontainersConfiguration.class)`, or the unreachable-database properties in `ApplicationWithoutDatabaseTest` and `DatabaseOutageTest`, which need no Docker. `src/test/resources/application-default.yml` applies to all of them.
- Spring Security's `with(user(...))` and `with(csrf())` in MockMvc change the filters for the rest of the context's life, and that can create sessions no real request would. To test anything about sessions, use real HTTP through `testsupport.ApiClient`, as `DatabaseOutageTest` does.
- `SecurityRulesIT` reads the routes from the application, so a new route is covered by its rules about signing in, CSRF and other users' data. A new kind of resource must be added to `ownedId` there.
- Tests never reach a real model: `application-default.yml` points Ollama at an unreachable address, and `testsupport.FakeAiConfiguration` provides predictable fakes. Integration tests import it with `TestcontainersConfiguration`. These fakes prove the plumbing, not the quality of answers, so changes to prompts, models or retrieval must also be run against the real models. `FakeChatModel` repeats the question when asked to rewrite it, unless `setRewriteReply` says otherwise, and `setDuringAnswer` runs something while the model works, such as a delete.
- `AuthFlowIT`, `DocumentFlowIT`, `QuestionFlowIT` and `ConversationFlowIT` use real HTTP through `testsupport.ApiClient`, which acts like the browser with cookies and CSRF. `testsupport.TestPdfs` generates real PDF files.
- A `@WebMvcTest` must `@Import(SecurityConfig.class)` to get the real rules. Requests then need `.with(user(new AccountPrincipal(...)))` and, if they change data, `.with(csrf())`.
- Frontend specs run on Vitest with jsdom. They find elements through `data-testid` and fake the backend with `HttpTestingController`. `ask.spec.ts` uses `RouterTestingHarness` with the real `askUrl` matcher, because the page depends on route reuse. That fake does not exercise what a browser really sends, so check request bodies and headers explicitly, and run authentication changes in a real browser. A `URLSearchParams` login body passed every spec while the browser sent it as JSON.
- Browser tests in `e2e/tests` use Playwright with the installed Edge, against the stack in `e2e/compose.yaml`, which has its own project name, volumes and ports, so stopping services and deleting everything never reaches real data ([ADR 0009](docs/adr/0009-browser-tests-on-an-isolated-stack.md)). `e2e/fake-ollama/server.mjs` replaces Ollama, and `fakeModel()` in `support/app.ts` makes it slow, broken or reply with chosen text. The stack sets low limits through environment variables, so a test of a limit takes seconds. Tests fail if the page throws or the CSP blocks something, so import `test` from `support/app`, not from Playwright. Use `whileStopped` to stop a service, because it always brings it back.
- `e2e/real` runs against the real models and must be run after changing a prompt, a model or the search. Its checks only ask for what a correct answer must contain, because models vary between runs.
- `e2e/load` holds the k6 script and `run.ts`, which starts the stack, samples resources and reports. k6 clears cookies between iterations unless told otherwise, has a 60-second request timeout of its own, and only reports a group of requests that has a threshold. A load test that fails all its requests in 5 ms looks fast, so keep its thresholds on failures. Results in `docs/performance.md` are written by hand from real runs, with their environment and method, and are never estimated without saying so.
- No test may call a paid API.
- Mockito's self-attach warning and PDFBox's fallback font warning are harmless.

## Keep in sync

- The pgvector image in `compose.yaml`, `e2e/compose.yaml` and `POSTGRES_IMAGE` in `TestcontainersConfiguration`.
- Forwarded paths in `frontend/proxy.conf.json` and `frontend/nginx.conf`.
- The upload limit in `spring.servlet.multipart` in `application.yml`, `client_max_body_size` in `frontend/nginx.conf` and `MAX_FILE_SIZE_BYTES` in `document.model.ts`.
- `docchat.rag.question-time-limit` and `spring.http.clients.read-timeout` in `application.yml` must stay below `proxy_read_timeout` in `frontend/nginx.conf`.
- The model names in `application.yml`, `compose.yaml` and `MODELS` in `e2e/compose.yaml`, and the vector dimension in the migrations and `e2e/fake-ollama/server.mjs` with the embedding model.
- The rules of `FakeEmbeddingModel` and `FakeChatModel` in the backend's tests and `e2e/fake-ollama/server.mjs`, and what that server answers with how the backend calls Ollama.
- The question length in `QuestionRequest` in `ConversationController` and `MAX_QUESTION_LENGTH` in `ask.ts`.
- The drawing and colours in `core/ui/brand-mark.ts` and `frontend/public/favicon.svg`, and `--color-ink` in `styles.scss` with `theme-color` in `index.html`.
- The captures in `frontend/public/showcase` with the screens they show, retaken when those change visibly.
- The measured times on the home page and the hardware table in the README, and the results in `docs/performance.md` with the summary under "Performance" in the README.
- The metric names in `observability/grafana/dashboards/docchat.json` with those the backend exposes. `e2e/tests/observability.spec.ts` fails when they differ.
- Backend response records and the TypeScript models in `frontend/src/app/core`, mirrored by hand with no code generation. `DocumentFailureReason` also needs a message in `FAILURE_MESSAGES` in `documents.ts`.

## Working rules

- The project stays on the user's machine. Do not add AWS or any other cloud service, deployment or hosted CI.
- The main Compose stack holds the user's real account and documents, and stopping its services interrupts their use of it, so try outages on the stack in `e2e/compose.yaml`. Never run `docker compose down -v` or remove its volumes to clear test data: accounts created for tests use `@example.com`, and only those and their files may be deleted.
- Use only what is free. Do not try, propose or configure anything paid.
- Commits stay local. Do not add a remote or push. Make one commit at the end of each phase, on a branch for that phase.
- Each phase's branch builds on the previous one, and `phase-6-performance-and-docs` is the only one with the whole application. Until the user decides to merge it, `main` holds only Phase 1, so do not base work or comparisons on `main`, and do not merge without asking. The branches of Phases 2 to 5 do not compile, because they lack the `com.docchat.storage` package.
- Code, documentation and UI text are in English.
- Consult the user before important decisions: base versions, deviations from the phase plan, machine installs, and anything else with a lasting effect. Significant decisions are recorded as ADRs in `docs/adr/`.
- Never report something as verified unless it was executed. If a check could not be run, say so and record it in the README's "Project status" section.
- Before closing a phase, run the tests from a clean `git clone`. Maven and Docker read the working folder, so passing there does not show that everything was committed. A `.gitignore` rule once kept a whole Java package out of every commit from Phase 2 to Phase 6, with every test passing. `git ls-files --others --ignored --exclude-standard` lists what Git is ignoring.
