# ADR 0010: Background document processing inside the application

**Status:** Accepted (2026-11-03)

## Context

Reading a PDF and embedding its passages takes from a second to several minutes, so an upload cannot wait for it. The work needs a place to run, a limit on how much runs at once, and an answer to what happens when the application or the database stops halfway. A message broker such as RabbitMQ or Kafka is the usual tool, but it would be one more service on a machine where memory is the tightest resource.

## Decision

**Processing runs inside the application, on a bounded pool.** An upload stores the file, inserts a row with the status `PROCESSING`, publishes an event and answers 202. The `ingestion` module listens for the event and queues the work on a pool with 2 workers and room for 100 waiting documents. A full queue fails the new document with a reason, instead of letting the work pile up without limit.

**The database row is the source of truth, not the queue.** The queue is in memory and is lost in a restart. So a check at startup, and every minute after it, finds the rows in `PROCESSING` that no worker has and queues them again. There is nothing else to keep consistent, and a document cannot be lost between a queue and a table.

**A document only fails for a reason that belongs to it or to the models.** A shutdown or a database outage says nothing about the file, so the document stays as it was and is processed again later. The other way round was a real bug found by the browser tests: if the database returned while saving was failing, the document ended as an internal error.

**One document cannot hold a worker forever.** Each one runs on its own daemon thread, and the worker only waits up to the time limit. A file can keep PDFBox busy in a way an interruption does not stop, and Java cannot end a thread from outside. So the document fails, the worker moves on, and the abandoned thread can do no harm: if it ever finishes, saving does nothing, because the document is no longer in `PROCESSING`.

## Consequences

- Nothing extra has to run, and a restart recovers by itself.
- It only works with one instance of the backend, because each instance would pick up the documents of the others. That matches a project that runs on one computer.
- A document can be processed from the start more than once, which repeats the embedding work but cannot duplicate data, because only the first to finish saves.
- An abandoned thread keeps using a processor core until the application restarts. This needs a file that makes the PDF library loop, and it was only tested with a simulation.
- Moving to several instances would need a way for them to claim documents, such as `SELECT ... FOR UPDATE SKIP LOCKED`, and not necessarily a broker.
