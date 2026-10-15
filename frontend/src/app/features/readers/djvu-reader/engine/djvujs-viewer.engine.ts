import {Injectable, NgZone, inject, signal} from '@angular/core';
import {DjvuEngine, DjvuOpenOptions, DjvuTheme} from './djvu-engine';

/** Vendored DjVu.js release files, see assets/djvujs/README.md. */
export const DJVUJS_LIBRARY_URL = '/assets/djvujs/djvu.js';
export const DJVUJS_VIEWER_URL = '/assets/djvujs/djvu_viewer.js';

/** The subset of the DjVu.js Viewer API that this engine uses. */
interface DjVuViewerState {
  pagesQuantity?: number | null;
  pageState?: {pageSizeList?: unknown[]};
}

interface DjVuViewerConfig {
  pageNumber?: number;
  viewMode?: 'continuous' | 'single' | 'text';
  theme?: DjvuTheme;
  uiOptions?: {
    hideOpenAndCloseButtons?: boolean;
    hidePrintButton?: boolean;
    hideSaveButton?: boolean;
  };
}

export interface DjVuViewerInstance {
  store: {getState(): DjVuViewerState; subscribe(listener: () => void): () => void};
  render(element: HTMLElement): void;
  loadDocument(buffer: ArrayBuffer, name?: string, config?: DjVuViewerConfig): Promise<void>;
  configure(config: DjVuViewerConfig): DjVuViewerInstance;
  getPageNumber(): number;
  on(event: string, handler: () => void): void;
  destroy(): void;
}

export interface DjVuViewerConstructor {
  new(config?: DjVuViewerConfig): DjVuViewerInstance;
  Events: {PAGE_NUMBER_CHANGED: string; DOCUMENT_CHANGED: string};
  get: {pagesQuantity(state: DjVuViewerState): number | null | undefined};
}

export interface DjVuGlobal {
  Viewer?: DjVuViewerConstructor;
}

const HIDDEN_UI_OPTIONS: DjVuViewerConfig['uiOptions'] = {
  hideOpenAndCloseButtons: true,
  hidePrintButton: true,
  hideSaveButton: true,
};

/** Upper bound for waiting on the viewer's page layout before jumping to the start page anyway. */
const LAYOUT_TIMEOUT_MS = 5000;

let scriptsLoading: Promise<DjVuViewerConstructor> | null = null;

function djvuGlobal(): DjVuGlobal | undefined {
  return (window as unknown as {DjVu?: DjVuGlobal}).DjVu;
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.async = false;
    script.onload = () => resolve();
    script.onerror = () => {
      script.remove();
      reject(new Error(`Failed to load ${src}`));
    };
    document.head.appendChild(script);
  });
}

/**
 * Loads the DjVu.js library and viewer scripts once per page. The viewer requires the
 * library's global {@code DjVu} object, so the scripts are loaded in order.
 */
export function loadDjvuJsViewer(): Promise<DjVuViewerConstructor> {
  const loaded = djvuGlobal()?.Viewer;
  if (loaded) {
    return Promise.resolve(loaded);
  }
  if (!scriptsLoading) {
    scriptsLoading = (async () => {
      if (!djvuGlobal()) {
        await loadScript(DJVUJS_LIBRARY_URL);
      }
      await loadScript(DJVUJS_VIEWER_URL);
      const viewer = djvuGlobal()?.Viewer;
      if (!viewer) {
        throw new Error('DjVu.js viewer did not initialise');
      }
      return viewer;
    })().catch(err => {
      scriptsLoading = null;
      throw err;
    });
  }
  return scriptsLoading;
}

function nextFrame(): Promise<void> {
  return new Promise(resolve => requestAnimationFrame(() => resolve()));
}

/** Resolves once the viewer knows every page size and has rendered its page list. */
async function whenLayoutReady(viewer: DjVuViewerInstance): Promise<void> {
  const ready = () => (viewer.store.getState().pageState?.pageSizeList?.length ?? 0) > 0;
  if (!ready()) {
    await new Promise<void>(resolve => {
      const timeout = setTimeout(done, LAYOUT_TIMEOUT_MS);
      const unsubscribe = viewer.store.subscribe(() => {
        if (ready()) {
          done();
        }
      });
      function done(): void {
        clearTimeout(timeout);
        unsubscribe();
        resolve();
      }
    });
  }
  // Let React render the page list so the viewer can scroll to the page
  await nextFrame();
  await nextFrame();
}

/** {@link DjvuEngine} backed by the ready-made DjVu.js Viewer (a React app with its own toolbar). */
@Injectable()
export class DjvuJsViewerEngine implements DjvuEngine {
  private readonly ngZone = inject(NgZone);

  private readonly pageSignal = signal(1);
  private readonly pageCountSignal = signal<number | null>(null);
  readonly page = this.pageSignal.asReadonly();
  readonly pageCount = this.pageCountSignal.asReadonly();

  private viewer: DjVuViewerInstance | null = null;
  private destroyed = false;

  async open(host: HTMLElement, data: ArrayBuffer, name: string, options: DjvuOpenOptions): Promise<void> {
    const Viewer = await loadDjvuJsViewer();
    if (this.destroyed) {
      return;
    }

    // The viewer re-renders on every mouse move and scroll; keep that out of Angular change detection
    await this.ngZone.runOutsideAngular(async () => {
      const viewer = new Viewer({theme: options.theme, uiOptions: HIDDEN_UI_OPTIONS});
      this.viewer = viewer;
      viewer.on(Viewer.Events.PAGE_NUMBER_CHANGED, () => this.syncState(Viewer));
      viewer.render(host);
      // Options passed with the document are applied after it is created; the viewer resets
      // its state on load, so the view options must go here rather than in the constructor.
      // Continuous scroll is used because single page mode (viewer 0.10.1) keeps showing the
      // previous page after a jump to a page that is not prefetched yet.
      await viewer.loadDocument(data, name, {
        viewMode: 'continuous',
        theme: options.theme,
        uiOptions: HIDDEN_UI_OPTIONS,
      });
      // In continuous mode a start page passed with the document is ignored, because it is
      // applied before the page layout exists; jump once the layout is ready instead
      if (options.page && options.page > 1) {
        await whenLayoutReady(viewer);
        if (this.viewer !== viewer) {
          return;
        }
        viewer.configure({pageNumber: options.page});
      }
      this.syncState(Viewer);
    });
  }

  goToPage(page: number): void {
    this.viewer?.configure({pageNumber: page});
  }

  setTheme(theme: DjvuTheme): void {
    this.viewer?.configure({theme});
  }

  destroy(): void {
    this.destroyed = true;
    const viewer = this.viewer;
    this.viewer = null;
    viewer?.destroy();
  }

  private syncState(Viewer: DjVuViewerConstructor): void {
    const viewer = this.viewer;
    if (!viewer) {
      return;
    }
    const page = viewer.getPageNumber();
    const count = Viewer.get.pagesQuantity(viewer.store.getState());
    this.ngZone.run(() => {
      if (page > 0) {
        this.pageSignal.set(page);
      }
      this.pageCountSignal.set(count && count > 0 ? count : null);
    });
  }
}
