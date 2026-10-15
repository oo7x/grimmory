import {ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, OnDestroy, OnInit, signal, untracked, viewChild} from '@angular/core';
import {Location} from '@angular/common';
import {HttpClient} from '@angular/common/http';
import {ActivatedRoute, Router} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {firstValueFrom, Subject} from 'rxjs';
import {debounceTime} from 'rxjs/operators';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {ProgressSpinner} from '@openng/optimus-ui/progressspinner';
import {API_CONFIG} from '../../../core/config/api-config';
import {Book, BookType} from '../../book/model/book.model';
import {BookService} from '../../book/service/book.service';
import {BookPatchService} from '../../book/service/book-patch.service';
import {PageTitleService} from '../../../shared/service/page-title.service';
import {CacheStorageService} from '../../../shared/service/cache-storage.service';
import {LocalSettingsService} from '../../../shared/service/local-settings.service';
import {ReadingSessionService} from '../../../shared/service/reading-session.service';
import {WakeLockService} from '../../../shared/service/wake-lock.service';
import {AppThemeService} from '../../../shared/service/app-theme.service';
import {ReaderIconComponent} from '../ebook-reader/shared/icon.component';
import {DJVU_ENGINE} from './engine/djvu-engine';
import {DjvuJsViewerEngine} from './engine/djvujs-viewer.engine';

/** Page counts as a percentage with one decimal, the format the other readers report. */
export function djvuProgressPercent(page: number, total: number | null): number {
  if (!total || total <= 0) {
    return 0;
  }
  return Math.round((Math.min(page, total) / total) * 1000) / 10;
}

@Component({
  selector: 'app-djvu-reader',
  standalone: true,
  imports: [ProgressSpinner, TranslocoPipe, ReaderIconComponent],
  // Swap this provider to change how DjVu pages are rendered; see DjvuEngine
  providers: [{provide: DJVU_ENGINE, useClass: DjvuJsViewerEngine}],
  templateUrl: './djvu-reader.component.html',
  styleUrl: './djvu-reader.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DjvuReaderComponent implements OnInit, OnDestroy {
  private static readonly PROGRESS_SAVE_DEBOUNCE_MS = 1000;

  private readonly engine = inject(DJVU_ENGINE);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly http = inject(HttpClient);
  private readonly bookService = inject(BookService);
  private readonly bookPatchService = inject(BookPatchService);
  private readonly pageTitle = inject(PageTitleService);
  private readonly cacheStorageService = inject(CacheStorageService);
  private readonly localSettingsService = inject(LocalSettingsService);
  private readonly readingSessionService = inject(ReadingSessionService);
  private readonly wakeLockService = inject(WakeLockService);
  private readonly appTheme = inject(AppThemeService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly viewerHost = viewChild.required<ElementRef<HTMLElement>>('viewerHost');

  readonly isLoading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly book = signal<Book | null>(null);
  readonly isOpen = signal(false);

  readonly title = computed(() => {
    const book = this.book();
    return book?.metadata?.title || book?.primaryFile?.fileName || '';
  });
  readonly page = this.engine.page;
  /** The viewer knows the real count once open; the scanned metadata covers the gap before that. */
  readonly totalPages = computed(() => this.engine.pageCount() ?? this.book()?.metadata?.pageCount ?? null);

  private bookFileId: number | null = null;
  private persistedPage: number | null = null;
  private readonly pageChanged$ = new Subject<number>();

  constructor() {
    effect(() => {
      const page = this.engine.page();
      if (untracked(this.isOpen)) {
        this.pageChanged$.next(page);
        this.readingSessionService.updateProgress(String(page), djvuProgressPercent(page, untracked(this.totalPages)));
      }
    });

    effect(() => {
      const theme = this.appTheme.effectiveAppearance();
      if (untracked(this.isOpen)) {
        this.engine.setTheme(theme);
      }
    });
  }

  ngOnInit(): void {
    this.pageChanged$.pipe(
      debounceTime(DjvuReaderComponent.PROGRESS_SAVE_DEBOUNCE_MS),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(page => this.saveProgress(page));

    const bookId = Number(this.route.snapshot.paramMap.get('bookId'));
    const altBookType = (this.route.snapshot.queryParamMap.get('bookType') ?? undefined) as BookType | undefined;
    void this.open(bookId, altBookType);
  }

  ngOnDestroy(): void {
    this.flushProgress();
    this.endSession();
    this.engine.destroy();
    void this.wakeLockService.disable();
  }

  closeReader(): void {
    this.flushProgress();
    this.endSession();
    // Navigate back within the SPA; fall back to home if there's no history
    if (window.history.length > 1) {
      this.location.back();
    } else {
      void this.router.navigate(['/']);
    }
  }

  private async open(bookId: number, altBookType: BookType | undefined): Promise<void> {
    let book: Book;
    let data: ArrayBuffer;
    try {
      book = await this.bookService.fetchFreshBookDetail(bookId, false);
      this.book.set(book);
      this.pageTitle.setBookPageTitle(book);

      const file = altBookType
        ? book.alternativeFormats?.find(f => f.bookType === altBookType)
        : book.primaryFile;
      this.bookFileId = file?.id ?? null;

      data = await this.fetchDocument(bookId, altBookType);
    } catch (err) {
      console.error('[DjVu Reader] Failed to load book', err);
      this.fail('shared.reader.failedToLoadBook');
      return;
    }

    const startPage = this.book()?.djvuProgress?.page ?? 1;
    this.persistedPage = this.book()?.djvuProgress?.page ?? null;
    const name = (altBookType
      ? book.alternativeFormats?.find(f => f.bookType === altBookType)?.fileName
      : book.primaryFile?.fileName) ?? this.title();

    try {
      await this.engine.open(this.viewerHost().nativeElement, data, name, {
        page: startPage,
        theme: this.appTheme.effectiveAppearance(),
      });
    } catch (err) {
      console.error('[DjVu Reader] Failed to open document', err);
      this.fail(err instanceof Error && err.message.startsWith('Failed to load')
        ? 'readerDjvu.failedToLoadViewer'
        : 'readerDjvu.failedToOpen');
      return;
    }

    this.isOpen.set(true);
    this.isLoading.set(false);
    setTimeout(() => void this.wakeLockService.enable(), 1000);

    const page = this.engine.page();
    this.readingSessionService.startSession(bookId, 'DJVU', String(page), djvuProgressPercent(page, this.totalPages()));
  }

  private fetchDocument(bookId: number, bookType: BookType | undefined): Promise<ArrayBuffer> {
    const uri = bookType
      ? `${API_CONFIG.BASE_URL}/api/v1/books/${bookId}/content?bookType=${bookType}`
      : `${API_CONFIG.BASE_URL}/api/v1/books/${bookId}/content`;
    if (this.localSettingsService.get().cacheStorageEnabled) {
      return this.cacheStorageService.getCache(uri).then(res => res.arrayBuffer());
    }
    return firstValueFrom(this.http.get(uri, {responseType: 'arraybuffer'}));
  }

  private saveProgress(page: number): void {
    const book = this.book();
    if (!book || this.bookFileId == null || page === this.persistedPage) {
      return;
    }
    this.persistedPage = page;
    this.bookPatchService.saveDjvuProgress(book.id, page, djvuProgressPercent(page, this.totalPages()), this.bookFileId)
      .subscribe({error: err => console.error('[DjVu Reader] Failed to save progress', err)});
  }

  /** Saves a page change that is still waiting for the debounce. */
  private flushProgress(): void {
    if (this.isOpen()) {
      this.saveProgress(this.engine.page());
    }
  }

  private endSession(): void {
    if (this.readingSessionService.isSessionActive()) {
      const page = this.engine.page();
      this.readingSessionService.endSession(String(page), djvuProgressPercent(page, this.totalPages()));
    }
  }

  private fail(messageKey: string): void {
    this.errorMessage.set(this.t.translate(messageKey));
    this.isLoading.set(false);
  }
}
