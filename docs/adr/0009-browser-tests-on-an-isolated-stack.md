# ADR 0009: Browser tests on an isolated stack

**Status:** Accepted (2026-11-03)

## Context

The browser checks of the earlier phases were one-off scripts run against the Compose stack, so they were not repeatable and were not part of this repository. They also ran next to real data: an early plan to clean up after a test by deleting the Compose volumes would have destroyed a user's account and documents. Testing failures means stopping the database and the backend, which a person using the application should not have to put up with. And real models answer in seconds, vary between runs, and need a GPU.

## Decision

**Playwright, using the Edge that comes with Windows.** It is free, and `channel: 'msedge'` means no browser has to be downloaded. The tests are in `e2e`, with their own `package.json`, because they test the whole stack and not the frontend's code.

**The tests have their own stack.** `e2e/compose.yaml` has its own project name, network, volumes and ports, and is built from the same Dockerfiles as the real one. The commands in `e2e/support/stack.ts` always name that project. Tests can therefore stop services and delete everything at the end, and the stack with real data can keep running beside them.

**By default, a small fake replaces Ollama.** `e2e/fake-ollama/server.mjs` speaks the part of Ollama's API that the backend uses, with the same rules as the fakes in the backend's tests, and tests can make it slow or broken through a control endpoint. The backend and its Spring AI client still run for real, over real HTTP. So these tests are fast and predictable, and they need no GPU. They prove that the parts work together and how failures are handled, not how good the answers are.

**The real models have a separate command.** `npm run test:real` runs a smaller set in `e2e/real` through the same isolated stack, connected to the Ollama of the main stack, with only the checks that need a real answer: the right page, across languages, nothing invented, instructions in a PDF not obeyed. It is run by hand after changing a prompt, a model or the search, which is what the fakes cannot judge.

**Limits are low in the test stack.** The time limits and the number of questions at once come from environment variables, so a test of what happens at a limit takes seconds and the production code is the same.

## Consequences

- The browser tests need Docker and Edge, and take a few minutes. They are not part of `./mvnw verify` or `npm test`.
- A second copy of the application uses memory while the tests run, so its Java heap is limited.
- The real-model command needs the main stack's `ollama` service to be running.
- Only Edge is tested. Playwright can also run Firefox and WebKit, but they need a download of about 300 MB, and no one has asked for them yet.
- The fake must follow any change to how the backend calls Ollama. A mismatch shows up as failing tests, not as a false pass, because the backend's real client parses its answers.
