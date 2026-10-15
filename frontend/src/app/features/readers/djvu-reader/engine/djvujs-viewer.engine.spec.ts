import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import type * as EngineModule from './djvujs-viewer.engine';

const DJVUJS_LIBRARY_URL = '/assets/djvujs/djvu.js';
const DJVUJS_VIEWER_URL = '/assets/djvujs/djvu_viewer.js';

const EVENTS = {PAGE_NUMBER_CHANGED: 'PAGE_NUMBER_CHANGED', DOCUMENT_CHANGED: 'DOCUMENT_CHANGED'};

interface FakeState {
  pagesQuantity: number;
  pageState: {pageSizeList: unknown[]};
}

/** Stands in for the DjVu.js Viewer: a page number, page layout, a page count and event handlers. */
class FakeViewer {
  static instances: FakeViewer[] = [];
  static Events = EVENTS;
  static get = {pagesQuantity: (state: FakeState) => state.pagesQuantity};
  /** Whether the page layout is ready when loadDocument resolves; the real viewer is usually later. */
  static layoutReadyOnLoad = true;

  pageNumber = 1;
  readonly handlers = new Map<string, () => void>();
  readonly listeners = new Set<() => void>();
  readonly state: FakeState = {pagesQuantity: 0, pageState: {pageSizeList: []}};
  readonly store = {
    getState: () => this.state,
    subscribe: (listener: () => void) => {
      this.listeners.add(listener);
      return () => this.listeners.delete(listener);
    },
  };
  readonly render = vi.fn();
  readonly destroy = vi.fn();
  readonly configure = vi.fn((config: {pageNumber?: number}) => {
    if (config.pageNumber) {
      this.flipTo(config.pageNumber);
    }
    return this;
  });
  readonly loadDocument = vi.fn(async () => {
    this.state.pagesQuantity = 120;
    if (FakeViewer.layoutReadyOnLoad) {
      this.layOutPages();
    }
  });

  layOutPages(): void {
    this.state.pageState = {pageSizeList: new Array(this.state.pagesQuantity).fill({width: 100, height: 140})};
    this.listeners.forEach(listener => listener());
  }

  constructor(readonly config: unknown) {
    FakeViewer.instances.push(this);
  }

  getPageNumber(): number {
    return this.pageNumber;
  }

  on(event: string, handler: () => void): void {
    this.handlers.set(event, handler);
  }

  flipTo(page: number): void {
    this.pageNumber = page;
    this.handlers.get(EVENTS.PAGE_NUMBER_CHANGED)?.();
  }
}

type DjVuWindow = Window & {DjVu?: {Viewer?: unknown}};

describe('DjvuJsViewerEngine', () => {
  let appended: HTMLScriptElement[];
  let DjvuJsViewerEngine: typeof EngineModule.DjvuJsViewerEngine;

  beforeEach(async () => {
    FakeViewer.layoutReadyOnLoad = true;
    // A fresh module per test: the loaded-scripts promise is cached at module level
    vi.resetModules();
    ({DjvuJsViewerEngine} = await import('./djvujs-viewer.engine'));
    FakeViewer.instances = [];
    appended = [];
    // Instead of downloading scripts, "load" them by defining what each one would define
    vi.spyOn(document.head, 'appendChild').mockImplementation(<T extends Node>(node: T): T => {
      const script = node as unknown as HTMLScriptElement;
      appended.push(script);
      const win = window as DjVuWindow;
      if (script.src.endsWith(DJVUJS_LIBRARY_URL)) {
        win.DjVu = {};
      } else if (script.src.endsWith(DJVUJS_VIEWER_URL)) {
        win.DjVu!.Viewer = FakeViewer;
      }
      queueMicrotask(() => script.onload?.(new Event('load')));
      return node;
    });
    TestBed.configureTestingModule({providers: [DjvuJsViewerEngine]});
  });

  afterEach(() => {
    delete (window as DjVuWindow).DjVu;
    vi.restoreAllMocks();
  });

  it('loads the library before the viewer, then reuses the loaded scripts', async () => {
    const host = document.createElement('div');
    await TestBed.inject(DjvuJsViewerEngine).open(host, new ArrayBuffer(4), 'a.djvu', {theme: 'dark'});
    await TestBed.runInInjectionContext(() => new DjvuJsViewerEngine())
      .open(host, new ArrayBuffer(4), 'b.djvu', {theme: 'dark'});

    expect(appended.map(s => new URL(s.src).pathname)).toEqual([DJVUJS_LIBRARY_URL, DJVUJS_VIEWER_URL]);
    expect(FakeViewer.instances).toHaveLength(2);
  });

  it('renders into the host in continuous mode, hiding file actions, then jumps to the start page', async () => {
    const engine = TestBed.inject(DjvuJsViewerEngine);
    const host = document.createElement('div');
    const data = new ArrayBuffer(4);

    await engine.open(host, data, 'atlas.djvu', {page: 7, theme: 'light'});

    const viewer = FakeViewer.instances[0];
    const uiOptions = {hideOpenAndCloseButtons: true, hidePrintButton: true, hideSaveButton: true};
    expect(viewer.config).toEqual({theme: 'light', uiOptions});
    expect(viewer.render).toHaveBeenCalledWith(host);
    expect(viewer.loadDocument).toHaveBeenCalledWith(data, 'atlas.djvu', {viewMode: 'continuous', theme: 'light', uiOptions});
    expect(viewer.configure).toHaveBeenCalledWith({pageNumber: 7});
    expect(engine.page()).toBe(7);
    expect(engine.pageCount()).toBe(120);
  });

  it('waits for the page layout before jumping to the start page', async () => {
    FakeViewer.layoutReadyOnLoad = false;
    const engine = TestBed.inject(DjvuJsViewerEngine);

    const opening = engine.open(document.createElement('div'), new ArrayBuffer(4), 'atlas.djvu', {page: 7, theme: 'dark'});
    await vi.waitFor(() => expect(FakeViewer.instances[0]?.loadDocument).toHaveBeenCalled());
    await Promise.resolve();
    const viewer = FakeViewer.instances[0];
    expect(viewer.configure).not.toHaveBeenCalled();

    viewer.layOutPages();
    await opening;

    expect(viewer.configure).toHaveBeenCalledWith({pageNumber: 7});
    expect(engine.page()).toBe(7);
  });

  it('opens on the first page without an extra jump', async () => {
    const engine = TestBed.inject(DjvuJsViewerEngine);
    await engine.open(document.createElement('div'), new ArrayBuffer(4), 'atlas.djvu', {page: 1, theme: 'dark'});

    expect(FakeViewer.instances[0].configure).not.toHaveBeenCalled();
    expect(engine.page()).toBe(1);
  });

  it('tracks page changes and forwards navigation and theme', async () => {
    const engine = TestBed.inject(DjvuJsViewerEngine);
    await engine.open(document.createElement('div'), new ArrayBuffer(4), 'atlas.djvu', {theme: 'dark'});
    const viewer = FakeViewer.instances[0];

    viewer.flipTo(15);
    expect(engine.page()).toBe(15);

    engine.goToPage(30);
    expect(viewer.configure).toHaveBeenCalledWith({pageNumber: 30});
    expect(engine.page()).toBe(30);

    engine.setTheme('light');
    expect(viewer.configure).toHaveBeenCalledWith({theme: 'light'});
  });

  it('destroys the viewer once and ignores calls afterwards', async () => {
    const engine = TestBed.inject(DjvuJsViewerEngine);
    await engine.open(document.createElement('div'), new ArrayBuffer(4), 'atlas.djvu', {theme: 'dark'});
    const viewer = FakeViewer.instances[0];

    engine.destroy();
    engine.destroy();
    engine.goToPage(3);

    expect(viewer.destroy).toHaveBeenCalledTimes(1);
    expect(viewer.configure).not.toHaveBeenCalled();
  });

  it('does not create a viewer when destroyed while the scripts load', async () => {
    const engine = TestBed.inject(DjvuJsViewerEngine);
    const opening = engine.open(document.createElement('div'), new ArrayBuffer(4), 'atlas.djvu', {theme: 'dark'});
    engine.destroy();
    await opening;

    expect(FakeViewer.instances).toHaveLength(0);
  });
});
