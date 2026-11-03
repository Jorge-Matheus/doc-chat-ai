import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { Showcase } from './showcase';

const FAST_MS = 25;

describe('Showcase', () => {
  let fixture: ComponentFixture<Showcase>;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const all = (id: string): HTMLElement[] =>
    Array.from(element().querySelectorAll<HTMLElement>(`[data-testid="${id}"]`));
  const one = (id: string): HTMLElement => all(id)[0];
  const activeIndex = (): number => all('showcase-slide').findIndex((slide) => slide.classList.contains('active'));
  const wait = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

  const create = async (intervalMs = 60_000): Promise<void> => {
    fixture = TestBed.createComponent(Showcase);
    fixture.componentRef.setInput('intervalMs', intervalMs);
    await fixture.whenStable();
  };

  afterEach(() => {
    fixture.destroy();
  });

  it('shows the first screen, with every image described and exposed only when active', async () => {
    await create();

    expect(activeIndex()).toBe(0);
    const slides = all('showcase-slide');
    expect(slides).toHaveLength(4);
    expect(slides[0].getAttribute('aria-hidden')).toBeNull();
    expect(slides.slice(1).every((slide) => slide.getAttribute('aria-hidden') === 'true')).toBe(true);
    for (const image of Array.from(element().querySelectorAll('img'))) {
      expect(image.getAttribute('alt')).toBeTruthy();
      expect(image.getAttribute('src')).toMatch(/^showcase\/.+\.webp$/);
    }
  });

  it('moves with the arrows and wraps around at both ends', async () => {
    await create();

    one('showcase-previous').click();
    await fixture.whenStable();
    expect(activeIndex()).toBe(3);

    one('showcase-next').click();
    await fixture.whenStable();
    expect(activeIndex()).toBe(0);
  });

  it('goes to the screen chosen in its tab, and changes the caption', async () => {
    await create();
    const firstCaption = one('showcase-caption').textContent;

    all('showcase-tab')[2].click();
    await fixture.whenStable();

    expect(activeIndex()).toBe(2);
    expect(all('showcase-tab')[2].getAttribute('aria-current')).toBe('true');
    expect(all('showcase-tab')[0].getAttribute('aria-current')).toBeNull();
    expect(one('showcase-caption').textContent).not.toBe(firstCaption);
  });

  it('moves by itself', async () => {
    await create(FAST_MS);

    await vi.waitFor(() => expect(activeIndex()).toBeGreaterThan(0));
  });

  it('stops when paused with its button and continues when played again', async () => {
    await create(FAST_MS);
    expect(one('showcase-toggle').getAttribute('aria-label')).toBe('Pause the tour');

    one('showcase-toggle').click();
    await fixture.whenStable();
    expect(one('showcase-toggle').getAttribute('aria-label')).toBe('Play the tour');
    const paused = activeIndex();
    await wait(FAST_MS * 4);
    expect(activeIndex()).toBe(paused);

    one('showcase-toggle').click();
    await vi.waitFor(() => expect(activeIndex()).not.toBe(paused));
  });

  it('stops while the pointer is over it, and announces changes only while stopped', async () => {
    await create(FAST_MS);
    expect(one('showcase-caption').getAttribute('aria-live')).toBe('off');

    element().querySelector('section')?.dispatchEvent(new MouseEvent('mouseenter'));
    await fixture.whenStable();
    const held = activeIndex();
    await wait(FAST_MS * 4);
    expect(activeIndex()).toBe(held);
    expect(one('showcase-caption').getAttribute('aria-live')).toBe('polite');

    element().querySelector('section')?.dispatchEvent(new MouseEvent('mouseleave'));
    await vi.waitFor(() => expect(activeIndex()).not.toBe(held));
  });

  it('stops while the keyboard focus is inside it', async () => {
    await create(FAST_MS);

    element().querySelector('section')?.dispatchEvent(new FocusEvent('focusin'));
    await fixture.whenStable();
    const held = activeIndex();
    await wait(FAST_MS * 4);
    expect(activeIndex()).toBe(held);

    element().querySelector('section')?.dispatchEvent(new FocusEvent('focusout'));
    await vi.waitFor(() => expect(activeIndex()).not.toBe(held));
  });

  it('does not move by itself for someone who asked for less motion', async () => {
    window.matchMedia = (() => ({ matches: true })) as unknown as typeof window.matchMedia;

    await create(FAST_MS);
    await wait(FAST_MS * 4);

    expect(activeIndex()).toBe(0);
    expect(one('showcase-toggle').getAttribute('aria-label')).toBe('Play the tour');
    delete (window as unknown as Record<string, unknown>)['matchMedia'];
  });

  it('stops moving when it leaves the page', async () => {
    await create(FAST_MS);
    const instance = fixture.componentInstance as unknown as { current(): number };

    fixture.destroy();
    const last = instance.current();
    await wait(FAST_MS * 4);

    expect(instance.current()).toBe(last);
  });
});
