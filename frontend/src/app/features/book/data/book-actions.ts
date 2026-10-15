import {type User} from '../../settings/user-management/user.service';
import {type BookFileResponse, type BookSummary} from './book-response.models';

type BookActionPermissionSource = Pick<
  User['permissions'],
  | 'admin'
  | 'canDownload'
  | 'canEmailBook'
  | 'canEditMetadata'
  | 'canDeleteBook'
  | 'canBulkResetGrimmoryReadProgress'
  | 'canBulkResetKoReaderReadProgress'
  | 'canMoveOrganizeFiles'
>;

interface BookActionPermissions {
  readonly canDownload: boolean;
  readonly canEmailBook: boolean;
  readonly canEditMetadata: boolean;
  readonly canDeleteBook: boolean;
  readonly canResetGrimmoryProgress: boolean;
  readonly canResetKoreaderProgress: boolean;
  readonly canOrganizeFiles: boolean;
}

export function bookActionPermissions(
  permissions: BookActionPermissionSource | null | undefined,
): BookActionPermissions {
  const admin = !!permissions?.admin;
  return {
    canDownload: admin || !!permissions?.canDownload,
    canEmailBook: admin || !!permissions?.canEmailBook,
    canEditMetadata: admin || !!permissions?.canEditMetadata,
    canDeleteBook: admin || !!permissions?.canDeleteBook,
    canResetGrimmoryProgress: admin || !!permissions?.canBulkResetGrimmoryReadProgress,
    canResetKoreaderProgress: admin || !!permissions?.canBulkResetKoReaderReadProgress,
    canOrganizeFiles: admin || !!permissions?.canMoveOrganizeFiles,
  };
}

export type BookReadAction = 'read' | 'continueReading' | 'play' | 'continueListening';

export function bookGrimmoryProgress(
  book: BookSummary,
  file: BookFileResponse | undefined = book.primaryFile,
): number | null {
  switch (file?.bookType) {
    case 'EPUB':
    case 'FB2':
    case 'MOBI':
    case 'AZW3':
      return book.epubProgress?.percentage ?? null;
    case 'PDF':
      return book.pdfProgress?.percentage ?? null;
    case 'CBX':
      return book.cbxProgress?.percentage ?? null;
    case 'AUDIOBOOK':
      return book.audiobookProgress?.percentage ?? null;
    case 'DJVU':
      return book.djvuProgress?.percentage ?? null;
    default:
      return null;
  }
}

export function bookProgressPercentage(
  book: BookSummary,
  file: BookFileResponse | undefined = book.primaryFile,
): number | null {
  const progress = bookGrimmoryProgress(book, file);
  if (file?.bookType === 'AUDIOBOOK') {
    return progress;
  }
  return progress ?? book.koreaderProgress?.percentage ?? book.koboProgress?.percentage ?? null;
}

function bookPartlyRead(book: BookSummary, file: BookFileResponse | undefined): boolean {
  const progress = bookProgressPercentage(book, file);
  return progress !== null && progress > 0 && progress < 100;
}

/** Every book file type has an in-app reader; kept as the single place to exclude manage-only types. */
export function bookFileHasReader(file: BookFileResponse | undefined): file is BookFileResponse {
  return file?.bookType != null;
}

/**
 * The file a Read action opens: the given file when it has a reader. A file without a
 * reader falls back to the first readable alternative format. No file means nothing to read.
 */
export function readableBookFile(
  book: BookSummary,
  file: BookFileResponse | undefined = book.primaryFile,
): BookFileResponse | undefined {
  if (file == null || bookFileHasReader(file)) {
    return file;
  }
  return (book.alternativeFormats ?? []).find(bookFileHasReader);
}

export function bookReadAction(
  book: BookSummary,
  file: BookFileResponse | undefined = book.primaryFile,
): BookReadAction {
  const audiobook = file?.bookType === 'AUDIOBOOK';
  if (bookPartlyRead(book, file)) {
    return audiobook ? 'continueListening' : 'continueReading';
  }
  return audiobook ? 'play' : 'read';
}
