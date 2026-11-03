import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Icon, IconName } from '../../core/ui/icon';

export const SLIDE_INTERVAL_MS = 7000;

interface Slide {
  readonly label: string;
  readonly icon: IconName;
  readonly caption: string;
  readonly image: string;
  readonly alt: string;
}

/** Real captures of this application, with sample documents and answers from the real models. */
const SLIDES: readonly Slide[] = [
  {
    label: 'Ask',
    icon: 'chat',
    caption: 'Ask in your own words, and get an answer that cites where it came from.',
    image: 'showcase/ask.webp',
    alt: 'A conversation with a question about a service agreement, an answer with a numbered citation, and the cited passage below it.',
  },
  {
    label: 'Follow up',
    icon: 'search',
    caption: 'Keep the conversation going. A short follow-up is understood from what came before.',
    image: 'showcase/follow-up.webp',
    alt: 'The follow-up question "And the penalty?", with the fuller question that was searched for, and its cited answer.',
  },
  {
    label: 'Check the source',
    icon: 'highlight',
    caption: 'Open any citation to see the passage marked on its page, or the original PDF.',
    image: 'showcase/source.webp',
    alt: 'A page of the service agreement opened in a viewer, with the cited passage marked in yellow.',
  },
  {
    label: 'Your documents',
    icon: 'file',
    caption: 'Upload your PDF files and follow them as they are read, all on this computer.',
    image: 'showcase/documents.webp',
    alt: 'A list of uploaded PDF files, each with its size, number of pages and status.',
  },
];

/**
 * A tour of the application. It moves by itself unless the user asked for less motion, and it stops
 * while the pointer or the keyboard focus is inside it, and when paused with its button.
 */
@Component({
  selector: 'app-showcase',
  imports: [Icon],
  templateUrl: './showcase.html',
  styleUrl: './showcase.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Showcase {
  readonly intervalMs = input(SLIDE_INTERVAL_MS);

  private readonly hovering = signal(false);
  private readonly focused = signal(false);

  protected readonly slides = SLIDES;
  protected readonly current = signal(0);
  protected readonly playing = signal(
    !inject(DOCUMENT).defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)').matches,
  );
  protected readonly running = computed(
    () => this.playing() && !this.hovering() && !this.focused(),
  );
  protected readonly currentSlide = computed(() => SLIDES[this.current()]);

  constructor() {
    // Reads the current slide too, so choosing one gives it its full time on the screen.
    effect((onCleanup) => {
      this.current();
      if (this.running()) {
        const timer = setTimeout(() => this.move(1), this.intervalMs());
        onCleanup(() => clearTimeout(timer));
      }
    });
  }

  protected select(index: number): void {
    this.current.set(index);
  }

  protected move(step: number): void {
    this.current.update((index) => (index + step + SLIDES.length) % SLIDES.length);
  }

  protected togglePlaying(): void {
    this.playing.update((playing) => !playing);
  }

  protected setHovering(hovering: boolean): void {
    this.hovering.set(hovering);
  }

  protected setFocused(focused: boolean): void {
    this.focused.set(focused);
  }
}
