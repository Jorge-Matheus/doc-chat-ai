# ADR 0005: PDF files on the local disk

**Status:** Accepted (2026-11-03)

## Context

Uploaded PDF files must be kept so they can be processed and, later, shown next to the answers. The project runs only on its owner's machine, with no deployment planned and no paid services.

## Decision

Store files in one directory on the local disk, which is a named Docker volume under Compose. Files are named after the document id, never after the name the client sent.

## Consequences

- There is no extra service to run, and no cost.
- A failed upload leaves nothing behind, because files are written to a temporary name and then moved.
- A database row and its file cannot change in one transaction. A file that cannot be deleted stays on disk as an orphan and is logged.
- Backups must cover both the database and the files.
- `docker compose down -v` deletes the files along with the database.
- There is deliberately no abstraction over the storage, because no second implementation is expected.
