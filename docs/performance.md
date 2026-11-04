# Performance

These numbers come from real runs of the commands below, on one computer, on 2026-11-03. They describe that computer and that day, not a promise, and another machine will differ. Most of the time of a real answer is the language model, so the runs measure the application and the models separately. See [ADR 0011](adr/0011-observability-and-load-tests.md).

## Summary

| Question | Result |
|---|---|
| How long does a person wait for an answer? | Median 0.76 s, and 95% under 0.88 s, on a short document with the models already loaded |
| And when the models have to be loaded first? | 48 s in this run. Other runs have seen up to 124 s, with under 2 GB of RAM free |
| How much of that is the application? | About 40 ms with one document: embedding the question, searching and storing |
| Does it hold up with several people at once? | Yes: 22 requests per second with no failures, and 95% of them under 50 ms, with the fake model |
| When does searching start to matter? | At 50,000 passages in one account, it takes about 0.6 s, as long as the model does |

## Environment

| | |
|---|---|
| Processor | AMD Ryzen 5 1600, with 12 threads |
| Memory | 16 GB, with 8 GB given to Docker |
| GPU | NVIDIA GeForce RTX 3060 with 12 GB |
| System | Windows 11 Home with Docker Desktop, Docker 29.9.2 |
| Models | `qwen3.5:9b` and `bge-m3`, in Ollama 0.40.14 |
| Application | The images built from this repository, with `-Xmx768m` for the backend and PostgreSQL with its default settings |
| Load generator | k6 2.3.0, in a container on the same computer |

The stack with real data was also running, and idle, as it would be on a real day. Desktop applications were open. The requests go through nginx, as a browser's do. Each run starts from an empty database.

## Several people at once, with a fake model

`npm run load` in `e2e`, on the stack of [ADR 0009](adr/0009-browser-tests-on-an-isolated-stack.md). The fake model answers at once, so this measures the application and not the GPU, and it can be repeated. For 3 minutes, on 10 accounts with a document of 40 pages each, it runs at the same time:

- 20 virtual people browsing: listing documents, listing conversations and reading a page, with a second between each action.
- 2 new questions per second, which is more than people ask, up to the limit of 4 answered at once.
- A new document of 3 pages every 10 seconds, followed until it is ready and then deleted.

That made 4,164 requests, or **22 per second, with none failing**, and no question was refused. This is the load that was tested, and not the most the application can take.

| Request | Count | p50 | p95 | p99 | Max |
|---|---:|---:|---:|---:|---:|
| List documents | 1,200 | 9 ms | 22 ms | 34 ms | 121 ms |
| List conversations | 1,200 | 8 ms | 19 ms | 29 ms | 35 ms |
| Read a page | 1,200 | 8 ms | 22 ms | 82 ms | 163 ms |
| Ask a question | 360 | 26 ms | 42 ms | 56 ms | 268 ms |
| Upload a file | 29 | 17 ms | 45 ms | 85 ms | 95 ms |
| Document until ready | 19 | 285 ms | 298 ms | 338 ms | 348 ms |

The time until a document is ready includes checking it every 250 ms, so it reads higher than the work itself.

Stages of a question, from the application's own metrics:

| Stage | p50 | p95 | p99 |
|---|---:|---:|---:|
| Embedding, with the fake model | 5.1 ms | 8.5 ms | 11.4 ms |
| Search | 1.3 ms | 2.4 ms | 3.4 ms |
| Generation, with the fake model | 5.5 ms | 9.6 ms | 11.6 ms |

Resources during the run, where 100% is one processor thread:

| Service | Average processor | Peak processor | Peak memory |
|---|---:|---:|---:|
| Backend | 31% | 241% | 418 MB |
| PostgreSQL | 8% | 17% | 87 MB |
| nginx | 2% | 4% | 16 MB |
| Fake model | 14% | 29% | 40 MB |
| Prometheus | 0% | 1% | 47 MB |
| Grafana | 1% | 3% | 163 MB |

The command fails if a threshold is passed, such as p95 over 500 ms for reading or any failed request above 1%. They are set far above these results, to catch a large regression.

## Real answers, one at a time

`npm run load:real` in `e2e`. It asks the real models 100 questions in a row, rotating 5 about a document of 4 pages, starting with the models unloaded. One of the 5 is unrelated to the document, so the search finds nothing and the language model is not asked.

| Measure | p50 | p95 | p99 | Max |
|---|---:|---:|---:|---:|
| Asking a question, as a client sees it | 757 ms | 876 ms | 1.41 s | 48.2 s |

The maximum is the first question, which waited for the model to load. The stages, with the models loaded:

| Stage | p50 | p95 | p99 |
|---|---:|---:|---:|
| Embedding the question, on the GPU | 36 ms | 67 ms | 154 ms |
| Search | 1.9 ms | 4.9 ms | 7.0 ms |
| Generation, on the GPU | 717 ms | 837 ms | 883 ms |

This document and these answers are short, and longer passages and answers take longer. The [hardware table](../README.md#what-to-expect-from-the-hardware) says 1 to 3 seconds for a typical answer, from earlier runs with more varied documents. A follow-up question also calls the model to rewrite it, which this run does not do.

## Searching a full account

`npm run load:scale` in `e2e`, with `E2E_SCALE_DOCUMENTS` set to the number of documents. It fills one account with documents of 2,000 pages, the largest allowed, each page being one passage, and asks 100 questions in a row with the fake model.

Searching compares the question with every passage of the account and uses no approximate index, on purpose, so that it finds the true closest passages. See the note in the migration `V5__create_document_chunks.sql`. So the time grows with the amount of text:

| Passages in the account | Search p50 | Search p95 | Search p99 | Whole question p50 |
|---:|---:|---:|---:|---:|
| 10,000 | 85 ms | 109 ms | 111 ms | 116 ms |
| 50,000 | 592 ms | 691 ms | 711 ms | 632 ms |

Repeating the 50,000 case with the same script gave a p50 of 771 ms, so these vary by about 30% between runs.

- The fake embeddings have the same 1024 dimensions as the real ones, and every dimension is compared, so the search does about the same work.
- Assuming the time keeps growing in proportion, a search would reach the limit of 5 seconds per database query at around 400,000 passages. This is an estimate, and it was not measured. That many passages in one account is far beyond what this project expects, and an approximate index is on the [roadmap](../README.md#roadmap) for it.
- The query scans the passages of all accounts before filtering by owner, as the plan with `EXPLAIN` showed, so other accounts' documents also add to the time.

## Not measured

- How many people at once it takes for the application to start failing, because the target machine serves one person.
- Several questions at once on the real models, which would only measure the queue for one GPU.
- Processing large documents with the real embedding model in this run. An earlier check took 110 seconds for 500 pages.
- Long periods of use, such as memory growth over days.

## Repeating these runs

From `e2e`, with Docker running and after `npm ci`. They start and delete their own stack, and they do not touch the main one, except that `load:real` needs its `ollama` service running.

```bash
npm run load                        # Several people at once, about 5 minutes
npm run load:real                   # Real models, one question at a time
E2E_SCALE_DOCUMENTS=25 npm run load:scale   # A full account
```

The numbers are printed at the end and saved in `e2e/load/results`, which Git ignores. Set `E2E_KEEP_STACK=1` to look at the run in Grafana, at <http://localhost:13000>, before the next run replaces the stack.
