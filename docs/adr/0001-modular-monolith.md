# ADR 0001: Modular monolith

**Status:** Accepted (2026-11-02)

## Context

DocChat AI needs authentication, document processing, RAG and chat. The project has one developer and no scale requirement that would justify independent deployments. Microservices, Kubernetes and Kafka would add operational work without improving the product at this stage.

## Decision

Build one Spring Boot application organized in packages by business module. A module is created only when it has real code, and the frontend talks to it only through the documented REST API.

## Consequences

- One artifact to build, test and deploy, and normal database transactions across modules.
- Module boundaries depend on discipline, because the compiler does not enforce them.
- Document processing shares the API's resources. This is accepted for the MVP and will be limited with a bounded executor.
- Extracting a module later would require replacing in-process calls and shared transactions.
