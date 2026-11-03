# ADR 0004: Server-side sessions with an HttpOnly cookie

**Status:** Accepted (2026-11-03)

## Context

Users need accounts. The browser only talks to one origin, the backend is a single application, and no other kind of client is planned. The usual alternatives are server-side sessions and JSON Web Tokens.

## Decision

Keep sessions on the server with Spring Session JDBC, in the same PostgreSQL database, and identify them with an HttpOnly cookie. Use Spring Security's own login and logout filters, and its cookie-based CSRF protection for single-page applications. Hash passwords with BCrypt.

## Consequences

- JavaScript never handles a credential, so a script injected into the page cannot read the session.
- Signing out takes effect immediately, and sessions survive backend restarts.
- Session fixation protection and CSRF token rotation come from the framework, not from custom code.
- Cookies require CSRF protection, so every state-changing request needs the token. Angular's HttpClient sends it automatically.
- Every authenticated request reads the session table, which is acceptable for this project.
- Login is a form post rather than JSON, because that is what the filter expects.
- The principal is serialized into the session table, so changing that class deliberately invalidates existing sessions.
