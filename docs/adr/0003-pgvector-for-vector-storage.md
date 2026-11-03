# ADR 0003: pgvector for vector storage

**Status:** Accepted (2026-11-02)

## Context

Semantic search needs embeddings to be stored and queried. Every query must also be restricted to documents the user owns, and deleting a document must remove its embeddings.

## Decision

Store embeddings in PostgreSQL with the pgvector extension, using the same database as the relational data, instead of running a dedicated vector database. The extension is enabled by the first Flyway migration.

## Consequences

- There is one system to run, back up and secure.
- Foreign keys can tie vectors to their documents, so ownership filters and deletion stay consistent.
- Spring AI has a pgvector integration, so vector operations do not need to be written by hand.
- Very large collections may eventually need a dedicated system. This is not expected for this project.
- Creating the extension needs enough database privileges, which managed PostgreSQL services may restrict.
- The vector dimension must match the embedding model, so it is defined in Phase 3 together with the provider.
