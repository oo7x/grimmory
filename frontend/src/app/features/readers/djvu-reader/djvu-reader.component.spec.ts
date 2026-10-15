import {Location} from '@angular/common';
import {HttpClient} from '@angular/common/http';
import {signal} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ActivatedRoute, convertToParamMap, Router} from '@angular/router';
import {of, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {getTranslocoModule} from '../../../core/testing/transloco-testing';
import {AppThemeService} from '../../../shared/service/app-theme.service';
import {CacheStorageService} from '../../../shared/service/cache-storage.service';
import {LocalSettingsService} from '../../../shared/service/local-settings.service';
import {PageTitleService} from '../../../shared/service/page-title.service';
import {ReadingSessionService} from '../../../shared/service/reading-session.service';
import {WakeLockService} from '../../../shared/service/wake-lock.service';
import {Book} from '../../book/model/book.model';
import {BookPatchService} from '../../book/service/book-patch.service';
import {BookService} from '../../book/service/book.service';
import {DjvuEngine, DjvuOpenOptions, DJVU_ENGINE, DjvuTheme} from './engine/djvu-engine';
import {DjvuReaderComponent, djvuProgressPercent} from './djvu-reader.component';

class FakeDjvuEngine implements DjvuEngine {
  readonly pageState = signal(1);
  readonly pageCountState = signal<number | null>(null);
  readonly page = this.pageState.asReadonly();
  readonly pageCount = this.pageCountState.asReadonly();
  readonly open = vi.fn(async (_host: HTMLElement, _data: ArrayBuffer, _name: string, options: DjvuOpenOptions) => {
    this.pageState.set(options.page ?? 1);
    this.pageCountState.set(40);
  });
  readonly goToPage = vi.fn((page: number) => this.pageState.set(page));
  readonly setTheme = vi.fn<(theme: DjvuTheme) => void>();
  readonly destroy = vi.fn();
}

function makeBook(overrides: Partial<Book> = {}): Book {
  return {
    id: 5,
    libraryId: 1,
    libraryName: 'Library',
    metadata: {bookId: 5, title: 'Scanned Atlas', pageCount: 38},
    primaryFile: {id: 50, bookId: 5, bookType: 'DJVU', fileName: 'atlas.djvu'},
    ...overrides,
  } as Book;
}

describe('djvuProgressPercent', () => {
  it('rounds to one decimal', () => {
    expect(djvuProgressPercent(1, 3)).toBe(33.3);
    expect(djvuProgressPercent(40, 40)).toBe(100);
  });

  it('returns 0 without a page count and caps at 100', () => {
    expect(djvuProgressPercent(5, null)).toBe(0);
    expect(djvuProgressPercent(50, 40)).toBe(100);
  });
});

describe('DjvuReaderComponent', () => {
  let fixture: ComponentFixture<DjvuReaderComponent>;
  let engine: FakeDjvuEngine;
  let book: Book;
  let queryParams: Record<string, string>;
  const bookService = {fetchFreshBookDetail: vi.fn()};
  const bookPatchService = {saveDjvuProgress: vi.fn()};
  const http = {get: vi.fn()};
  const cacheStorageService = {getCache: vi.fn()};
  const readingSessionService = {
    startSession: vi.fn(),
    updateProgress: vi.fn(),
    endSession: vi.fn(),
    isSessionActive: vi.fn(() => true),
  };
  const location = {back: vi.fn()};
  const router = {navigate: vi.fn()};
  const appearance = signal<'light' | 'dark'>('dark');

  beforeEach(() => {
    vi.clearAllMocks();
    engine = new FakeDjvuEngine();
    book = makeBook();
    queryParams = {};
    bookService.fetchFreshBookDetail.mockImplementation(async () => book);
    bookPatchService.saveDjvuProgress.mockReturnValue(of(undefined));
    http.get.mockReturnValue(of(new ArrayBuffer(8)));
    appearance.set('dark');
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function create(): void {
    TestBed.configureTestingModule({
      imports: [DjvuReaderComponent, getTranslocoModule()],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({bookId: '5'}),
              queryParamMap: convertToParamMap(queryParams),
            },
          },
        },
        {provide: BookService, useValue: bookService},
        {provide: BookPatchService, useValue: bookPatchService},
        {provide: HttpClient, useValue: http},
        {provide: CacheStorageService, useValue: cacheStorageService},
        {provide: LocalSettingsService, useValue: {get: () => ({cacheStorageEnabled: false})}},
        {provide: ReadingSessionService, useValue: readingSessionService},
        {provide: WakeLockService, useValue: {enable: vi.fn(async () => undefined), disable: vi.fn(async () => undefined)}},
        {provide: PageTitleService, useValue: {setBookPageTitle: vi.fn()}},
        {provide: AppThemeService, useValue: {effectiveAppearance: appearance.asReadonly()}},
        {provide: Location, useValue: location},
        {provide: Router, useValue: router},
      ],
    });
    TestBed.overrideComponent(DjvuReaderComponent, {
      set: {providers: [{provide: DJVU_ENGINE, useValue: engine}]},
    });
    fixture = TestBed.createComponent(DjvuReaderComponent);
    fixture.detectChanges();
  }

  async function opened(): Promise<void> {
    await vi.waitFor(() => expect(fixture.componentInstance.isOpen()).toBe(true));
    fixture.detectChanges();
  }

  function text(testId: string): string | undefined {
    return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`)?.textContent?.trim();
  }

  it('loads the primary file and opens it on the saved page with the app theme', async () => {
    book = makeBook({djvuProgress: {page: 12, percentage: 30}});
    appearance.set('light');
    create();
    await opened();

    expect(http.get).toHaveBeenCalledWith(expect.stringMatching(/\/api\/v1\/books\/5\/content$/), {responseType: 'arraybuffer'});
    expect(engine.open).toHaveBeenCalledWith(
      expect.any(HTMLElement), expect.any(ArrayBuffer), 'atlas.djvu', {page: 12, theme: 'light'});
    expect(readingSessionService.startSession).toHaveBeenCalledWith(5, 'DJVU', '12', 30);
    expect(text('page-indicator')).toContain('12');
    expect(text('loading')).toBeUndefined();
  });

  it('opens an alternative DJVU format with its own file id', async () => {
    queryParams = {bookType: 'DJVU'};
    book = makeBook({
      primaryFile: {id: 50, bookId: 5, bookType: 'EPUB', fileName: 'atlas.epub'},
      alternativeFormats: [{id: 51, bookId: 5, bookType: 'DJVU', fileName: 'atlas-scan.djv'}],
    });
    create();
    await opened();

    expect(http.get).toHaveBeenCalledWith(expect.stringMatching(/\/content\?bookType=DJVU$/), {responseType: 'arraybuffer'});
    expect(engine.open.mock.calls[0][2]).toBe('atlas-scan.djv');

    vi.useFakeTimers();
    engine.goToPage(3);
    fixture.detectChanges();
    vi.advanceTimersByTime(1000);
    expect(bookPatchService.saveDjvuProgress).toHaveBeenCalledWith(5, 3, 7.5, 51);
  });

  it('saves page changes after a debounce, once per page', async () => {
    create();
    await opened();
    vi.useFakeTimers();

    engine.goToPage(2);
    fixture.detectChanges();
    engine.goToPage(4);
    fixture.detectChanges();
    vi.advanceTimersByTime(999);
    expect(bookPatchService.saveDjvuProgress).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(bookPatchService.saveDjvuProgress).toHaveBeenCalledTimes(1);
    expect(bookPatchService.saveDjvuProgress).toHaveBeenCalledWith(5, 4, 10, 50);
    expect(readingSessionService.updateProgress).toHaveBeenLastCalledWith('4', 10);
  });

  it('flushes unsaved progress, ends the session and releases the engine on destroy', async () => {
    create();
    await opened();

    engine.goToPage(20);
    fixture.detectChanges();
    fixture.destroy();

    expect(bookPatchService.saveDjvuProgress).toHaveBeenCalledWith(5, 20, 50, 50);
    expect(readingSessionService.endSession).toHaveBeenCalledWith('20', 50);
    expect(engine.destroy).toHaveBeenCalled();
  });

  it('follows app theme changes once open', async () => {
    create();
    await opened();

    appearance.set('light');
    fixture.detectChanges();

    expect(engine.setTheme).toHaveBeenCalledWith('light');
  });

  it('closes back to the previous page', async () => {
    create();
    await opened();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-testid="close-reader"]')!.click();

    expect(readingSessionService.endSession).toHaveBeenCalled();
    expect(window.history.length > 1 ? location.back : router.navigate).toHaveBeenCalled();
  });

  it('shows an error when the file cannot be downloaded', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    http.get.mockReturnValue(throwError(() => new Error('404')));
    create();

    await vi.waitFor(() => expect(fixture.componentInstance.errorMessage()).not.toBeNull());
    fixture.detectChanges();

    expect(engine.open).not.toHaveBeenCalled();
    expect(text('error')).toBeTruthy();
    expect(readingSessionService.startSession).not.toHaveBeenCalled();
  });

  it('shows an error when the document cannot be opened', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    engine.open.mockRejectedValueOnce(new Error('Corrupted file'));
    create();

    await vi.waitFor(() => expect(fixture.componentInstance.errorMessage()).not.toBeNull());

    expect(fixture.componentInstance.isOpen()).toBe(false);
    expect(readingSessionService.startSession).not.toHaveBeenCalled();
  });
});
