# ADR 0007: Local AI with Ollama

**Status:** Accepted (2026-11-03)

## Context

Answering questions needs an embedding model and a language model. The project must stay on its owner's machine and use only free software, so hosted model APIs are out. That machine has an NVIDIA RTX 3060 with 12 GB of memory and 16 GB of RAM.

## Decision

Run [Ollama](https://ollama.com) as a Compose service with access to the GPU, and download the models into a named volume on the first run. Use `bge-m3` for embeddings and `qwen3.5:9b` for answers. Both can be changed by environment variables.

Spring AI is used only to reach the models, through `EmbeddingModel` and `ChatModel`. Its pgvector store is not used: passages live in `document_chunks`, with foreign keys to their page and document as [ADR 0003](0003-pgvector-for-vector-storage.md) intends, and are searched with plain SQL filtered by owner. A search covers few rows, so it scans exactly and needs no approximate index.

The models were chosen by measuring them on that machine. `gemma4:e4b` was about twice as fast but once answered an English question in Portuguese, and the answers of both were otherwise equivalent. `bge-m3` matched questions and passages across Portuguese and English: related pairs scored 0.66 or more, and unrelated ones about 0.26.

Measured with the whole stack and desktop applications running:

| Measure | Result |
|---|---|
| Layers on the GPU | 100% of both models |
| Peak GPU memory | About 8.7 GB of 12 GB, including what the desktop uses |
| Typical answer | 1 to 3 seconds |
| First answer after the model is unloaded | About 20 seconds, and 124 seconds in the worst case seen, with under 2 GB of RAM free |
| Processing a PDF of 500 pages (3493 passages) | 110 seconds |

## Consequences

- Nothing leaves the machine, there is no account or API key, and there is no cost.
- `compose.yaml` requires an NVIDIA GPU that Docker can reach. Removing `gpus: all` runs on the CPU, which was not measured and will be much slower.
- The first `docker compose up` downloads about 8 GB.
- RAM is tighter than GPU memory: the Docker VM used 7.6 GB of the 16 GB, and little was left with a browser open. Nothing failed, but models load more slowly under that pressure.
- Other applications that need the GPU compete with the models. Ollama unloads a model after five idle minutes, which frees that memory.
- The AI service is not part of readiness, so accounts and documents keep working without it. Questions answer 503, and uploads fail with a specific reason until it returns.
- Changing the embedding model to one of another dimension needs a new migration and reprocessing every document.
- Local models can be wrong. Citations are resolved by the server and every answer must cite a passage, but only the reader can confirm that the passage supports the answer.
- Instructions hidden in a PDF are treated as untrusted text. In tests with the real model, none was obeyed, and two of twenty made it answer "not found". This reduces the risk without eliminating it.
