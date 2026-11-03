import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Icon } from '../../core/ui/icon';

/** The dark panel beside the sign-in and registration forms. It is hidden on small screens. */
@Component({
  selector: 'app-auth-aside',
  imports: [Icon],
  template: `
    <h2>Every answer traces back to <span class="marked">a page</span>.</h2>
    <ul>
      <li><app-icon name="check" /><span>Upload your PDF files</span></li>
      <li><app-icon name="check" /><span>Ask in your own words</span></li>
      <li><app-icon name="check" /><span>See the passage, marked on its page</span></li>
    </ul>
    <p class="privacy">
      <app-icon name="lock" />
      <span>Your account and your documents stay on this computer.</span>
    </p>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      padding: 2.5rem;
      background:
        radial-gradient(26rem 20rem at 100% 0, rgb(45 180 160 / 20%), transparent 70%),
        linear-gradient(160deg, var(--color-ink) 0%, var(--color-ink-soft) 100%);
      color: var(--color-on-ink);
    }

    h2 {
      margin: 0;
      color: var(--color-on-ink);
      font-size: 2rem;
      line-height: 1.2;
    }

    .marked {
      padding: 0 0.14em;
      border-radius: 0.1em;
      background: var(--color-marker-strong);
      color: var(--color-ink);
    }

    ul {
      display: grid;
      gap: 0.75rem;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    li,
    .privacy {
      display: flex;
      gap: 0.625rem;
      align-items: center;
    }

    li app-icon {
      color: var(--color-marker-strong);
    }

    .privacy {
      margin: auto 0 0;
      color: var(--color-on-ink-muted);
      font-size: 0.875rem;
    }

    @media (max-width: 52rem) {
      :host {
        display: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthAside {}
