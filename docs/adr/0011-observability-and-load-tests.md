# ADR 0011: Observability and load tests

**Status:** Accepted (2026-11-03)

## Context

Knowing whether the application is slow, and where, needs measurements of each stage: extraction, embedding, search and generation. The target machine has little memory to spare, and everything must be free and stay on that machine. Load tests also have a problem specific to this project: a real model answers one question at a time on one GPU, so testing with it would only measure the GPU.

## Decision

**Metrics come from Micrometer, through the Observation API.** Each stage of a question and of a document is an observation with a fixed name, a `stage` or `outcome` tag, and the `error` tag Micrometer adds. Spring AI's own observations add the tokens of the language model. Cost is not measured, because the models run on this computer and have none.

**Tags hold only fixed words.** No email, file name, id, question or answer can reach the metrics, and a test checks that. `/actuator/prometheus` is public because Prometheus cannot sign in, but it is only on the backend's port, which Compose binds to 127.0.0.1, and the frontend does not forward it.

**Prometheus and Grafana are optional.** They are a Compose profile, in `observability/compose.yaml`, because together they use over 200 MB that a person asking questions may not want to spend. The dashboard comes from a file, and Grafana is read-only with no login, so there is no password to manage. Its analytics and update checks are off, so nothing leaves the computer.

**A test keeps the dashboard honest.** A browser test reads the metric names of every panel and checks them against what the application exposes, so renaming a metric cannot silently leave a panel empty.

**Documents are counted on a schedule.** Counting the documents in each state runs every 15 seconds and not when the metrics are read, so reading them never waits for the database. During an outage is when metrics matter most.

**Load tests use k6 in a container, against the isolated stack of [ADR 0009](0009-browser-tests-on-an-isolated-stack.md), with the fake model.** This measures the application itself and can be repeated. Two separate runs answer what it cannot: the time of real answers, one at a time, and of searching an account filled with the largest documents allowed. Results, with their environment and method, are in [docs/performance.md](../performance.md), and are written by hand from real runs.

## Consequences

- Nothing has to be installed, and the main stack does not change unless the profile is turned on.
- Percentiles use histograms, which add many series per metric. That is fine for one application and one Prometheus.
- The load test has thresholds, so it can fail, and they are set well above what was measured. They catch a large regression, and they are not promises about performance.
- The simulated numbers say nothing about how long a real answer takes, which is why the real ones are measured separately.
- Results on another machine will differ. The same commands repeat the method.
