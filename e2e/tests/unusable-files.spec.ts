import {
  chooseFile,
  csrfToken,
  documentRow,
  expect,
  fakeModel,
  register,
  test,
  upload,
  uploadAndWait,
} from '../support/app';
import { CONTRACT, CORRUPT, NO_TEXT, pdf } from '../support/pdfs';

const MEGABYTE = 1024 * 1024;

test.beforeEach(async ({ page }) => {
  await fakeModel();
  await register(page);
});

test('a text file renamed to .pdf is refused, and nothing appears in the list', async ({ page }) => {
  await chooseFile(page, 'notes.pdf', Buffer.from('These are just notes, not a PDF.'));

  await expect(page.getByTestId('action-error')).toContainText('Only PDF files are accepted');
  await expect(page.getByTestId('document')).toHaveCount(0);
});

test('an empty file is refused', async ({ page }) => {
  await chooseFile(page, 'empty.pdf', Buffer.alloc(0));

  await expect(page.getByTestId('action-error')).toContainText('The file is empty');
  await expect(page.getByTestId('document')).toHaveCount(0);
});

test('a damaged PDF fails with its reason, and trying again is not offered', async ({ page }) => {
  await upload(page, 'damaged.pdf', CORRUPT);

  const row = documentRow(page, 'damaged.pdf');
  await expect(row.getByText('Failed', { exact: true })).toBeVisible();
  await expect(row.getByTestId('document-failure')).toContainText('damaged or is not a valid PDF');
  await expect(row.getByTestId('retry')).toHaveCount(0);
  await expect(row.getByTestId('open-file')).toHaveCount(0);
});

test('a PDF without text fails with its reason', async ({ page }) => {
  await upload(page, 'scanned.pdf', NO_TEXT);

  const row = documentRow(page, 'scanned.pdf');
  await expect(row.getByTestId('document-failure')).toContainText('no selectable text');
  await expect(row.getByTestId('retry')).toHaveCount(0);
});

test('a failed document can be deleted', async ({ page }) => {
  await upload(page, 'damaged.pdf', CORRUPT);
  await expect(documentRow(page, 'damaged.pdf').getByText('Failed', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Delete damaged.pdf' }).click();
  await page.locator('mat-dialog-actions').getByRole('button', { name: 'Delete' }).click();

  await expect(page.getByTestId('document')).toHaveCount(0);
});

test('a file over the limit is refused before it is sent', async ({ page }) => {
  let sent = false;
  page.on('request', (request) => {
    sent ||= request.method() === 'POST' && request.url().endsWith('/api/documents');
  });

  await chooseFile(page, 'huge.pdf', Buffer.alloc(25 * MEGABYTE + 1, '%PDF-1.7 '));

  await expect(page.getByTestId('action-error')).toContainText('larger than the 25 MB limit');
  expect(sent).toBe(false);
});

test('the server refuses a file over the limit too, for requests that skip the page', async ({ page, context }) => {
  const response = await context.request.post('/api/documents', {
    headers: { 'X-XSRF-TOKEN': await csrfToken(context) },
    multipart: {
      file: {
        name: 'huge.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.alloc(25 * MEGABYTE + 1024, '%PDF-1.7 '),
      },
    },
  });

  expect(response.status()).toBe(413);
  expect(((await response.json()) as { code: string }).code).toBe('CONTENT_TOO_LARGE');
  await page.reload();
  await expect(page.getByTestId('empty')).toBeVisible();
});

test('a request larger than the proxy accepts gets an error, and the page explains it', async ({ context }) => {
  const response = await context.request.post('/api/documents', {
    headers: { 'X-XSRF-TOKEN': await csrfToken(context) },
    multipart: {
      file: { name: 'huge.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(27 * MEGABYTE, '%PDF-1.7 ') },
    },
  });

  expect(response.status()).toBe(413);
});

test('files sent at the same time from two tabs are both processed and kept apart', async ({ page, context }) => {
  const second = await context.newPage();
  await second.goto('/documents');
  await expect(second.getByTestId('empty')).toBeVisible();

  await Promise.all([
    chooseFile(page, 'first.pdf', pdf(['The first file talks about apples.'])),
    chooseFile(second, 'second.pdf', pdf(['The second file talks about oranges.'])),
  ]);

  await expect(documentRow(page, 'first.pdf').getByText('Ready', { exact: true })).toBeVisible();
  await expect(documentRow(second, 'second.pdf').getByText('Ready', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByTestId('document')).toHaveCount(2);
  await expect(documentRow(page, 'second.pdf').getByText('Ready', { exact: true })).toBeVisible();
  await second.close();
});

test('the same file sent twice becomes two documents, and deleting one leaves the other', async ({ page }) => {
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await chooseFile(page, 'contract.pdf', CONTRACT);
  await expect(page.getByTestId('document')).toHaveCount(2);
  await expect(page.getByTestId('document').getByText('Ready', { exact: true })).toHaveCount(2);

  await page.getByRole('button', { name: 'Delete contract.pdf' }).first().click();
  await page.locator('mat-dialog-actions').getByRole('button', { name: 'Delete' }).click();

  await expect(page.getByTestId('document')).toHaveCount(1);
  await expect(page.getByTestId('open-file')).toBeVisible();
});

test('a document deleted while it is processing stays deleted', async ({ page }) => {
  await fakeModel({ embedDelayMs: 2000 });
  await upload(page, 'contract.pdf', CONTRACT);
  await expect(documentRow(page, 'contract.pdf').getByText('Processing', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Delete contract.pdf' }).click();
  await page.locator('mat-dialog-actions').getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByTestId('document')).toHaveCount(0);

  await page.waitForTimeout(3000);
  await page.reload();
  await expect(page.getByTestId('empty')).toBeVisible();
});
