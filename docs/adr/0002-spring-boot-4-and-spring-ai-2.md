# ADR 0002: Spring Boot 4.1 and Spring AI 2.0

**Status:** Accepted (2026-11-02)

## Context

The original project brief named Spring Boot 3.5.x, while allowing another stable version if the ecosystem required it. When the project started:

- Spring Boot 3.5 had left open-source support on 2026-06-30, with 3.5.16 as its last release.
- Spring Boot 4.1.2 was the latest stable release, with open-source support until 2027-07-31.
- Spring AI 2.0 was the current stable line and is designed for Spring Boot 4.0 and 4.1. Spring AI 1.1 targets Spring Boot 3.5.

## Decision

Use Spring Boot 4.1.2 and Spring AI 2.0.2, on Java 21.

## Consequences

- The project starts on a line that still receives free security fixes.
- Spring Boot 4 uses modular starters, such as `spring-boot-starter-webmvc`, and new package names, such as `org.springframework.boot.health.contributor`. Older tutorials need adapting.
- Spring AI 2.0 uses Jackson 3. Some libraries still bring Jackson 2 onto the classpath, so both coexist.
- The Spring AI starters are only added in Phase 3, when their compatibility with this version of Spring Boot will be confirmed in a real build.
