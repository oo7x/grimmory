import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {getTranslocoModule} from '../../../../core/testing/transloco-testing';
import {UrlHelperService} from '../../../../shared/service/url-helper.service';
import {BookSummary} from '../../data/book-response.models';
import {BookCardComponent, bookCardCoverSrc} from './book-card.component';

function makeBook(overrides: Partial<BookSummary> = {}): BookSummary {
  return {
    id: 1,
    libraryId: 1,
    libraryName: 'Library',
    metadata: {
      bookId: 1,
      title: 'The Warden',
      authors: ['Anthony Trollope'],
      allMetadataLocked: false,
    },
    primaryFile: {id: 10, bookId: 1, book: true, folderBased: false, bookType: 'EPUB'},
    ...overrides,
  };
}

function audiobookFile() {
  return {id: 10, bookId: 1, book: true, folderBased: false, bookType: 'AUDIOBOOK' as const};
}

describe('BookCardComponent', () => {
  let fixture: ComponentFixture<BookCardComponent>;
  let host: HTMLElement;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [BookCardComponent, getTranslocoModule()],
      providers: [
        provideRouter([{path: '**', children: []}]),
        {
          provide: UrlHelperService,
          useValue: {
            getThumbnailUrl: (id: number) => `/thumb/${id}`,
            getAudiobookThumbnailUrl: (id: number) => `/audio-thumb/${id}`,
          },
        },
      ],
    });

    fixture = TestBed.createComponent(BookCardComponent);
    host = fixture.nativeElement as HTMLElement;
    router = TestBed.inject(Router);
  });

  function progressWidth(book: BookSummary): string | undefined {
    fixture.componentRef.setInput('book', book);
    fixture.detectChanges();
    return host.querySelector<HTMLElement>('[data-testid="progress"] i')?.style.width;
  }

  function readButton(book: BookSummary): HTMLButtonElement | null {
    fixture.componentRef.setInput('book', book);
    fixture.detectChanges();
    const buttons = host.querySelectorAll<HTMLButtonElement>('[data-testid="overlay"] button');
    // The overlay always has the menu button; a second one is the Read action
    return buttons.length > 1 ? buttons[0] : null;
  }

  it('shows the read action for readable files', () => {
    expect(readButton(makeBook())).not.toBeNull();
  });

  it('hides the read action for DJVU books, which have no reader', () => {
    const djvu = {id: 10, bookId: 1, book: true, folderBased: false, bookType: 'DJVU' as const};
    expect(readButton(makeBook({primaryFile: djvu}))).toBeNull();
  });

  it('keeps the read action for DJVU books with a readable alternative format', () => {
    const djvu = {id: 10, bookId: 1, book: true, folderBased: false, bookType: 'DJVU' as const};
    const epub = {id: 11, bookId: 1, book: true, folderBased: false, bookType: 'EPUB' as const};
    expect(readButton(makeBook({primaryFile: djvu, alternativeFormats: [epub]}))).not.toBeNull();
  });

  it('shows the progress of the file it is showing', () => {
    expect(progressWidth(makeBook({
      epubProgress: {cfi: null, href: null, contentSourceProgressPercent: null, percentage: 30, ttsPositionCfi: null},
      pdfProgress: {page: 1, percentage: 50},
      koreaderProgress: {percentage: 42},
    }))).toBe('30%');
    expect(progressWidth(makeBook({
      primaryFile: audiobookFile(),
      audiobookProgress: {positionMs: 1000, trackIndex: null, trackPositionMs: null, percentage: 82},
    }))).toBe('82%');
    expect(progressWidth(makeBook({koreaderProgress: {percentage: 42}, koboProgress: {percentage: 12}}))).toBe('42%');
    expect(progressWidth(makeBook({primaryFile: audiobookFile(), koreaderProgress: {percentage: 42}})))
      .toBeUndefined();
    expect(progressWidth(makeBook())).toBeUndefined();
  });

  it('uses client-side navigation for an ordinary link click', () => {
    fixture.componentRef.setInput('book', makeBook());
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const event = new MouseEvent('click', {button: 0, cancelable: true});

    (fixture.componentInstance as unknown as {openDetails(event: MouseEvent): void}).openDetails(event);

    expect(event.defaultPrevented).toBe(true);
    expect(navigate).toHaveBeenCalledWith(['/book', 1]);
  });

  it('swallows the click generated after a long press', () => {
    fixture.componentRef.setInput('book', makeBook());
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const component = fixture.componentInstance as unknown as {
      longPressFired: boolean;
      openDetails(event: MouseEvent): void;
    };
    component.longPressFired = true;
    const event = new MouseEvent('click', {button: 0, cancelable: true});

    component.openDetails(event);

    expect(event.defaultPrevented).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });
});

describe('bookCardCoverSrc', () => {
  const urlHelper = {
    getThumbnailUrl: (id: number) => `/thumb/${id}`,
    getAudiobookThumbnailUrl: (id: number) => `/audio-thumb/${id}`,
  } as UrlHelperService;

  it('picks the artwork for the file it is given', () => {
    const book = makeBook();
    expect(bookCardCoverSrc(book, book.primaryFile, urlHelper)).toBe('/thumb/1');
    expect(bookCardCoverSrc(book, audiobookFile(), urlHelper)).toBe('/audio-thumb/1');
    expect(bookCardCoverSrc(book, undefined, urlHelper)).toBe('/thumb/1');
  });
});
