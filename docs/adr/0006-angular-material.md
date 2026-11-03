# ADR 0006: Angular Material for the interface

**Status:** Accepted (2026-11-03)

## Context

From Phase 2 on, the interface needs forms, dialogs and pagination, all of which are easy to get wrong for accessibility.

## Decision

Use Angular Material, with the system font and without icon fonts, so the application loads nothing from external services.

## Consequences

- The library is maintained by the Angular team, follows its releases and has an MIT license.
- Accessible forms, dialogs and pagination do not have to be written or tested by hand.
- The look follows Material Design, with less visual freedom than custom components.
- It adds weight to the bundle. Pages are lazily loaded, and the initial bundle stays under the 500 kB warning budget.
