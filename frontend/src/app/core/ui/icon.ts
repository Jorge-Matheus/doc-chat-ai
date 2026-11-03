import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const ICONS = {
  alert: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 8v5', 'M12 16.5h.01'],
  check: ['M5 12.5l4.5 4.5L19 7.500'],
  chat: ['M21 12a8 8 0 0 1-8 8H4l2.5-3A8 8 0 1 1 21 12z'],
  'chevron-left': ['M15 6l-6 6 6 6'],
  'chevron-right': ['M9 6l6 6-6 6'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
  external: ['M14 5h5v5', 'M19 5l-8 8', 'M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4'],
  file: ['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z', 'M14 3v5h5', 'M9 13h6', 'M9 17h6'],
  highlight: ['M4 20h16', 'M6 16l2.5-1 9-9a1.8 1.8 0 0 0-2.5-2.5l-9 9z'],
  lock: ['M7 11V8a5 5 0 0 1 10 0v3', 'M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z'],
  logout: ['M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3', 'M10 8l-4 4 4 4', 'M6 12h10'],
  pause: ['M9 5v14', 'M15 5v14'],
  play: ['M8 5l11 7-11 7z'],
  plus: ['M12 5v14', 'M5 12h14'],
  question: [
    'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z',
    'M9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.8.4-1.1.9-1.1 1.8',
    'M12 17h.01',
  ],
  refresh: ['M20 11a8 8 0 0 0-14.3-4.3L4 8.500', 'M4 4v4.500h4.500', 'M4 13a8 8 0 0 0 14.300 4.300l1.700-1.800', 'M20 20v-4.500h-4.500'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M20 20l-4-4'],
  send: ['M12 19V5', 'M6 11l6-6 6 6'],
  trash: ['M4 7h16', 'M10 11v6', 'M14 11v6', 'M6 7l1 13h10l1-13', 'M9 7V4h6v3'],
  upload: ['M12 16V4', 'M7 9l5-5 5 5', 'M4 20h16'],
} as const;

export type IconName = keyof typeof ICONS;

/** Drawn here as inline SVG, so no icon font or other external file is needed. Purely decorative. */
@Component({
  selector: 'app-icon',
  template: `
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.75"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      @for (path of paths(); track path) {
        <svg:path [attr.d]="path" />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      width: 1.125em;
      height: 1.125em;
      vertical-align: -0.2em;
    }

    svg {
      width: 100%;
      height: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Icon {
  readonly name = input.required<IconName>();

  protected readonly paths = computed(() => ICONS[this.name()]);
}
