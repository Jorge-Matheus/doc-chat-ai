# DocChat AI

DocChat AI is a full-stack platform, under construction, for asking questions about PDF documents in natural language and getting answers backed by verifiable citations to the source pages. It is built with Java, Spring Boot, RAG (Retrieval-Augmented Generation), PostgreSQL with pgvector, Angular and local AI models.

It runs only on your own machine. Your documents never leave it: there is no cloud service and no deployment, and it uses only free software.

> **Current state:** accounts, PDF upload and questions with cited answers work (Phases 1 to 3). Conversation history and a viewer for the source pages are not implemented yet. See [Project status](#project-status).

## The problem

Finding a specific answer in a long document, such as a 40-page contract, means reading it or searching for exact words. Generic AI chat can answer faster, but its answers are hard to trust without knowing where they came from. DocChat AI answers only from the user's own documents, cites the pages it used, and says so when the documents do not contain the answer.

## Project status

| Phase | Scope | Status |
|---|---|---|
| 1. Foundation | Backend, frontend, database, migrations, health checks, Docker | Complete |
| 2. Authentication and documents | Accounts, authorization, PDF upload and extraction | Complete |
| 3. RAG | Chunking, embeddings, vector search, LLM answers with citations | Complete |
| 4. Conversations and interface | History, source viewer, complete UI flow | Planned |
| 5. Tests and robustness | End-to-end and security tests, failure recovery | Planned |
| 6. Performance and documentation | Observability, load tests, final documentation | Planned |

**How Phases 1 to 3 were checked:** the automated tests listed under [Tests](#tests), which use fake models, plus runs on the developer's machine of the Compose stack and of the development setup. With the stack running, stopping the database, the backend and the AI service was tried by hand, as was upgrading a database that already had documents. The complete flow was run in headless Microsoft Edge with the real models, including a PDF of 500 pages. Those browser checks use one-off scripts that are not part of this repository, so they are not repeated automatically yet.

## Architecture

The backend is a [modular monolith](docs/adr/0001-modular-monolith.md). The browser only talks to one origin, and the API is proxied behind it.

```mermaid
flowchart TD
    Browser[Browser] --> Web["Angular app<br/>nginx in Docker, ng serve in development"]
    Web -->|"/api, same origin"| API["Spring Boot REST API"]
    API --> DB[("PostgreSQL + pgvector<br/>accounts, sessions, documents, pages, passages")]
    API --> Files["PDF files on the local disk"]
    API --> AI["Ollama on the local GPU<br/>embedding and language models"]
```

Backend packages today:

| Package | Responsibility |
|---|---|
| `com.docchat.common` | Correlation ids, Problem Details error format, OpenAPI configuration |
| `com.docchat.system` | Status endpoint and pgvector health indicator |
| `com.docchat.auth` | Accounts, login, sessions, CSRF protection and access rules |
| `com.docchat.document` | Document records, upload rules and ownership |
| `com.docchat.storage` | PDF files on the local disk and the storage health indicator |
| `com.docchat.ingestion` | Background text extraction, splitting and embedding, with a bounded queue |
| `com.docchat.ai` | Access to the local models and their availability |
| `com.docchat.rag` | Passage search, the prompt, and answers with verified citations |

A new module (`chat`) is added when it has real code.

**Processing a document.** An upload is answered right after the file is stored, and the document starts as `PROCESSING`. A small pool of workers extracts the text of each page, splits it into overlapping passages that never cross a page, embeds them and saves everything in one transaction. The document then becomes `READY`, or `FAILED` with a reason. The interface checks every two seconds while a document is processing. Documents interrupted by a shutdown are processed again at the next startup.

**Answering a question.**

1. The question is embedded, and the closest passages among the user's own `READY` documents are found. Those too different from the question are dropped. If none remains, the answer is "not found" and the language model is not called.
2. The language model receives the passages, numbered, and must answer only from them, citing their numbers. It never sees file names or page numbers.
3. The server checks the reply. Citations of passages the model was not given are removed, and a reply with no valid citation counts as "not found". Each remaining number becomes the real file and page, which is how citations stay truthful.

## Tech stack

| Technology | Version | Why |
|---|---|---|
| Java | 21 LTS | Required language level. The build also runs on JDK 25. |
| Spring Boot | 4.1.2 | Current supported line. See [ADR 0002](docs/adr/0002-spring-boot-4-and-spring-ai-2.md). |
| Spring Security and Spring Session JDBC | 7.1 and 4.1 (managed by Spring Boot) | Server-side sessions with an HttpOnly cookie. See [ADR 0004](docs/adr/0004-server-side-sessions.md). |
| Spring AI | 2.0.2 | Access to the models without tying the code to one provider. |
| Ollama | 0.40.14 | Runs open models on the local GPU, with no account or cost. See [ADR 0007](docs/adr/0007-local-ai-with-ollama.md). |
| `bge-m3`, `qwen3.5:9b` | Set by environment variables | Multilingual embeddings and answers, chosen by measuring them on an RTX 3060. |
| PostgreSQL + pgvector | 18 + 0.8.7 | One database for relational data and vectors. See [ADR 0003](docs/adr/0003-pgvector-for-vector-storage.md). |
| Flyway | 12.4.0 (managed by Spring Boot) | Versioned schema migrations. |
| Apache PDFBox | 3.0.8 | Extracts the text of each PDF page. |
| springdoc-openapi | 3.2.0 | OpenAPI document and Swagger UI. |
| Angular | 22.2 | Standalone components, signals and strict TypeScript. |
| Angular Material | 22.2 | Accessible forms, dialogs and pagination. See [ADR 0006](docs/adr/0006-angular-material.md). |
| JUnit, Mockito, Testcontainers, Vitest | Managed by Spring Boot and Angular | Automated tests with no paid APIs, models or GPU. |

## Getting started

Requires Docker with Compose and an NVIDIA GPU that Docker can reach. The models use about 6 GB of GPU memory.

Copy the example configuration first, then change `POSTGRES_PASSWORD` in `.env`:

```bash
cp .env.example .env
```

### Run everything in Docker

```bash
docker compose up --build
```

The first run downloads about 8 GB of models, and the backend starts after that. Then open <http://localhost:4200>, create an account, upload a PDF and ask about it. The home page shows the live status of the backend, the database and the AI models.

### Run for development

Also requires JDK 21 or newer and Node.js 26 (or another [version supported by Angular 22](https://angular.dev/reference/versions)).

```bash
docker compose up -d db ollama ollama-models

cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

cd frontend
npm ci
npm start
```

Open <http://localhost:4200>. `ng serve` proxies the API to the backend on port 8080. Uploaded files go to `backend/storage`, which Git ignores.

On Windows, use `mvnw.cmd` instead of `./mvnw`. In Windows PowerShell, also quote the property, because PowerShell splits an unquoted argument at the dot and Maven fails with `Unknown lifecycle phase`:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

### What to expect from the hardware

Measured on an RTX 3060 with 12 GB and 16 GB of RAM, with the whole stack and desktop applications running:

| Measure | Result |
|---|---|
| Layers on the GPU | 100% of both models |
| Peak GPU memory | About 8.7 GB of 12 GB, including what the desktop uses |
| Typical answer | 1 to 3 seconds |
| First answer after the model is unloaded | About 20 seconds, and 124 seconds in the worst case seen, with under 2 GB of RAM free |
| Processing a PDF of 500 pages | 110 seconds |

Ollama unloads a model after five idle minutes, which frees its GPU memory and makes the next answer slower. RAM is tighter than GPU memory on such a machine, so closing other applications helps most when loading is slow.

### Environment variables

| Variable | Used by | Description |
|---|---|---|
| `POSTGRES_DB` | Compose, `dev` profile | Database name |
| `POSTGRES_USER` | Compose, `dev` profile | Database user |
| `POSTGRES_PASSWORD` | Compose, `dev` profile | Database password, with no default on purpose |
| `POSTGRES_PORT`, `BACKEND_PORT`, `FRONTEND_PORT`, `OLLAMA_PORT` | Compose, `dev` profile | Host ports, bound to 127.0.0.1 |
| `DOCCHAT_DB_URL`, `DOCCHAT_DB_USERNAME`, `DOCCHAT_DB_PASSWORD` | `prod` profile | Database connection. Compose sets these for the container. |
| `DOCCHAT_STORAGE_DIR` | Backend | Directory for uploaded files. Compose sets it to a named volume. The default is `storage`, relative to the working directory. |
| `OLLAMA_CHAT_MODEL` | Compose, backend | Language model. The default is `qwen3.5:9b`. |
| `OLLAMA_EMBEDDING_MODEL` | Compose, backend | Embedding model. The default is `bge-m3`. A model of another dimension also needs a new migration and reprocessing every document. |
| `OLLAMA_BASE_URL` | Backend | Address of Ollama. Compose sets it for the container. |

## API

The API is documented with OpenAPI at `/v3/api-docs`, and Swagger UI is at `/swagger-ui/index.html`.

| Endpoint | Description |
|---|---|
| `POST /api/auth/register` | Creates an account from JSON with `email` and `password`. Passwords need at least 12 characters and at most 72 bytes. |
| `POST /api/auth/login` | Signs in from a form with `email` and `password`. Answers 204 and sets the session cookie. |
| `POST /api/auth/logout` | Ends the session. Answers 204. |
| `GET /api/auth/me` | The signed-in user. Answers 401 when no one is signed in. |
| `POST /api/documents` | Uploads a PDF of up to 25 MB as the multipart part `file`. Answers 202. |
| `GET /api/documents?page=0&size=20` | The user's documents, newest first, with `items`, `page`, `size` and `totalItems`. |
| `GET /api/documents/{id}` | One document, with its `status`, `failureReason` and `pageCount`. |
| `DELETE /api/documents/{id}` | Deletes the document, its text, its passages and its file. Answers 204. |
| `POST /api/questions` | Answers a question from JSON with `question` (up to 2000 characters) and optionally `documentIds`. Answers 503 with the code `AI_UNAVAILABLE` when the models cannot be reached. |
| `GET /api/system/status` | Real state of each dependency, including the AI models. Answers 200 while the backend runs. Public. |
| `GET /actuator/health/readiness` | Readiness probe. Answers 503 if the database, pgvector or the file storage is unavailable. The AI service is left out on purpose, because accounts and documents work without it. Public. |
| `GET /actuator/health/liveness` | Liveness probe. Public. |

Requests that change data must send the value of the `XSRF-TOKEN` cookie in the `X-XSRF-TOKEN` header. Any response from the backend sets that cookie.

```bash
curl http://localhost:8080/api/system/status
```

```json
{ "status": "UP", "database": "UP", "vectorExtension": "UP", "ai": "UP" }
```

An answer looks like this. When the documents do not contain the answer, `answered` is `false`, `text` is `null` and `citations` is empty.

```json
{
  "answered": true,
  "text": "The notice period is 60 days [1].",
  "citations": [
    {
      "number": 1,
      "documentId": "6c5baffe-63a2-4bb0-a913-a55c3eb89a07",
      "filename": "contract.pdf",
      "pageNumber": 2,
      "excerpt": "Clause 7. The contract can be terminated with 60 days notice..."
    }
  ]
}
```

Errors use [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with `code`, `timestamp` and `correlationId` members, plus `errors` when request fields are invalid. Every response has an `X-Correlation-Id` header, and the same id appears in the logs, including those of the document's background processing.

A `FAILED` document has one of these reasons:

| `failureReason` | Meaning |
|---|---|
| `NOT_READABLE` | The file is damaged or is not really a PDF |
| `PROTECTED` | The file needs a password or forbids text extraction |
| `NO_EXTRACTABLE_TEXT` | No page has text, as in a scanned PDF |
| `TOO_MANY_PAGES` | The file has more than 2000 pages |
| `PROCESSING_UNAVAILABLE` | The processing queue was full |
| `EMBEDDING_UNAVAILABLE` | The embedding model could not be reached |
| `INTERNAL_ERROR` | An unexpected failure, recorded in the logs |

## Tests

| Command | Runs | Needs Docker |
|---|---|---|
| `./mvnw test` (in `backend`) | Unit and web tests, including the full application context with an unreachable database | No |
| `./mvnw verify` (in `backend`) | The above, plus integration tests over real HTTP against real PostgreSQL + pgvector, with fake models | Yes. It fails without Docker. |
| `npm test -- --watch=false` (in `frontend`) | Component and service tests | No |
| `npm run lint` (in `frontend`) | ESLint | No |
| `npm run build` (in `frontend`) | Production build | No |

No test calls a paid API or needs a GPU. Test PDFs are generated by the tests themselves. Automated tests prove the plumbing with predictable fake models, so they do not measure how good the real models' answers are.

## Security

This version is meant to run on your own machine and **must not be exposed publicly**, because it does not yet have the protections listed under [Known limitations](#known-limitations). Today:

- Passwords are hashed with BCrypt.
- Sessions live in PostgreSQL behind an HttpOnly, SameSite=Lax cookie, and signing in replaces the session id.
- Requests that change data need a CSRF token.
- Each user reaches only their own documents and passages. Another user's document answers 404, so its existence is not revealed.
- Uploads are checked by their PDF signature and size, and files on disk are named after the document id, never after the name the client sent.
- Document text is treated as untrusted when it reaches the model, and it is displayed as text, never as markup.
- Questions and document contents are not written to the logs.
- Compose binds every published port to 127.0.0.1.
- Only the `health` and `info` Actuator endpoints are exposed, and health responses omit component details.
- Error responses never include stack traces or internal messages.
- Secrets come from the environment, and `.env` is ignored by Git.
- The backend and frontend containers run as non-root users.

## Troubleshooting

| Message | Cause | Fix |
|---|---|---|
| `Failed to configure a DataSource: 'url' attribute is not specified` | No Spring profile is active | Run with the `dev` or `prod` profile |
| `'url' must start with "jdbc"` | The `prod` profile is active and `DOCCHAT_DB_URL` is not set | Set the `DOCCHAT_DB_*` variables |
| `password authentication failed` in the `dev` profile | `.env` is missing, or its password differs from the one the database volume was created with | Check `.env` in the repository root. To start over, run `docker compose down -v`, which deletes the database, the uploaded files and the downloaded models. |
| `Could not find a valid Docker environment` during `./mvnw verify` | Docker is not running | Start Docker, or use `./mvnw test` |
| `403` with the code `FORBIDDEN` on a request that changes data | The request has no CSRF token | Send the `XSRF-TOKEN` cookie's value in the `X-XSRF-TOKEN` header |
| Docker cannot start `ollama` because of the GPU | Docker cannot reach an NVIDIA GPU | Update the driver and check with `docker run --rm --gpus all ubuntu:24.04 nvidia-smi` |
| The home page shows the AI models as down | Ollama is stopped or a model is missing | Run `docker compose up -d ollama ollama-models` |

## Known limitations

- A local model can misread a passage, so confirm important answers against the cited excerpt.
- Scanned PDFs are refused, because there is no text recognition (OCR).
- Passages never cross a page, so a sentence spanning two pages is cut in two.
- Questions are answered one at a time, with no memory of earlier questions (Phase 4).
- The answer appears all at once, not word by word.
- Instructions hidden in a PDF were never obeyed in tests with the real model, but they sometimes made it answer "not found", and this risk cannot be fully eliminated.
- The threshold for dropping unrelated passages was measured on a small set of questions.
- A document that failed because the AI service was down must be deleted and uploaded again.
- There is no email verification, password reset or account deletion.
- Login has no rate limit, and uploads have no per-user quota.
- Processing one PDF has no time limit. The number of workers is limited, so a pathological file can hold a worker but cannot exhaust the server's threads.
- A stored PDF cannot be downloaded or viewed yet (Phase 4).
- Swagger UI's "Try it out" does not send the CSRF token, so only read requests work there.
- The UI has automated component tests but no automated browser tests yet (Phase 5).
- Mockito prints a self-attach warning during backend tests, and PDFBox logs a fallback font warning for PDFs using standard fonts. Neither affects results.

## Architecture decisions

- [ADR 0001: Modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR 0002: Spring Boot 4.1 and Spring AI 2.0](docs/adr/0002-spring-boot-4-and-spring-ai-2.md)
- [ADR 0003: pgvector for vector storage](docs/adr/0003-pgvector-for-vector-storage.md)
- [ADR 0004: Server-side sessions with an HttpOnly cookie](docs/adr/0004-server-side-sessions.md)
- [ADR 0005: PDF files on the local disk](docs/adr/0005-local-file-storage.md)
- [ADR 0006: Angular Material for the interface](docs/adr/0006-angular-material.md)
- [ADR 0007: Local AI with Ollama](docs/adr/0007-local-ai-with-ollama.md)
