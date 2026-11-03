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

## Update: a look of its own

Material's default look felt generic for a product about reading documents, so its theme tokens are overridden from a small set of colours in `styles.scss`: warm paper, a deep green for the navigation bar and the dark sections, and the yellow of a highlighter, because marking the source of an answer is what the application does. Headings use a serif font that comes with the system, and icons are inline SVG drawn in `core/ui/icon.ts`, so nothing is loaded from external services and no dependency was added. The question box is a plain textarea rather than a Material form field, which leaves the application responsible for its label and its error message.

The home page has a tour with real captures of the application, in `frontend/public/showcase`, instead of stock images. They show the actual product, with sample documents written for them and answers from the real models, and have no licensing questions. The cost is that they go out of date, so they must be retaken when the screens change visibly. The carousel is written here rather than taken from a library, which keeps the bundle small and makes its accessibility our responsibility: it stops on hover, on focus and with its own button, and does not move by itself for users who asked for reduced motion.

All the colour pairs used for text were measured at 4.5:1 or more, which is what WCAG AA asks for.
