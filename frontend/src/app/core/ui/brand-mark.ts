import { ChangeDetectionStrategy, Component } from '@angular/core';

/** A page with one line marked, which is what the application does. Same drawing as favicon.svg. */
@Component({
  selector: 'app-brand-mark',
  template: `
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <rect class="tile" width="32" height="32" rx="8" />
      <path class="page" d="M10 6h8.5L24 11.5V25a1.5 1.5 0 0 1-1.5 1.5h-12.500A1.500 1.500 0 0 1 8.500 25V7.500A1.500 1.500 0 0 1 10 6z" />
      <rect class="line" x="11.500" y="11.500" width="5" height="1.500" rx="0.750" />
      <rect class="marker" x="10.500" y="15" width="11" height="3.500" rx="0.750" />
      <rect class="line" x="11.500" y="16" width="9" height="1.500" rx="0.750" />
      <rect class="line" x="11.500" y="20.500" width="7" height="1.500" rx="0.750" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      width: 2rem;
      height: 2rem;
    }

    svg {
      width: 100%;
      height: 100%;
    }

    .tile {
      fill: var(--brand-tile, var(--color-accent));
    }

    .page {
      fill: var(--color-surface);
    }

    .marker {
      fill: #f6d743;
    }

    .line {
      fill: var(--color-accent-strong);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandMark {}
