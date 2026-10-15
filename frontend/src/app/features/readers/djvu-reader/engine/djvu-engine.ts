import {InjectionToken, Signal} from '@angular/core';

export type DjvuTheme = 'dark' | 'light';

export interface DjvuOpenOptions {
  /** 1-based page to open the document on. */
  page?: number;
  theme: DjvuTheme;
}

/**
 * Renders a DjVu document inside a host element.
 *
 * The reader component only talks to this interface, so the rendering backend can be swapped
 * (e.g. the bundled DjVu.js Viewer today, a custom canvas UI built on the DjVu.js library later)
 * without touching routing, progress saving or reading sessions.
 */
export interface DjvuEngine {
  /** Current 1-based page number. */
  readonly page: Signal<number>;
  /** Total page count, or null until the document is open. */
  readonly pageCount: Signal<number | null>;

  /** Renders the document into {@code host}. Resolves once the document is open. */
  open(host: HTMLElement, data: ArrayBuffer, name: string, options: DjvuOpenOptions): Promise<void>;

  goToPage(page: number): void;

  setTheme(theme: DjvuTheme): void;

  /** Releases the renderer, its worker and the document. Safe to call more than once. */
  destroy(): void;
}

export const DJVU_ENGINE = new InjectionToken<DjvuEngine>('DJVU_ENGINE');
