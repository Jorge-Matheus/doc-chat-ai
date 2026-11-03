import {
  ask,
  confirmDeletion,
  documentRow,
  expect,
  fakeModel,
  fakeModelCalls,
  openAsk,
  register,
  sendQuestion,
  signIn,
  test,
  upload,
  uploadAndWait,
} from '../support/app';
import { CONTRACT, HANDBOOK } from '../support/pdfs';

const NOTICE_QUESTION = 'What is the notice period to terminate the contract?';

test.beforeEach(async () => {
  await fakeModel();
});

test('a new account starts with an empty list of documents and survives signing out', async ({ page }) => {
  const email = await register(page);

  await expect(page.getByTestId('empty')).toBeVisible();
  await expect(page.getByTestId('user-email')).toHaveText(email);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByTestId('user-email')).toHaveCount(0);

  await page.goto('/login');
  await signIn(page, email);
  await expect(page.getByTestId('user-email')).toHaveText(email);
});

test('an uploaded document goes from processing to ready without reloading the page', async ({ page }) => {
  await register(page);
  await fakeModel({ embedDelayMs: 3000 });

  await upload(page, 'contract.pdf', CONTRACT);

  const row = documentRow(page, 'contract.pdf');
  await expect(row.getByText('Processing', { exact: true })).toBeVisible();
  await expect(row.getByText('Ready', { exact: true })).toBeVisible();
  await expect(row).toContainText('4 pages');
  await expect(row.getByTestId('retry')).toHaveCount(0);
});

test('a document still processing cannot be asked about yet', async ({ page }) => {
  await register(page);
  await fakeModel({ embedDelayMs: 5000 });
  await upload(page, 'contract.pdf', CONTRACT);

  await page.getByRole('link', { name: 'Ask', exact: true }).click();

  await expect(page.getByTestId('no-documents')).toBeVisible();
  await expect(page.getByTestId('question')).toHaveCount(0);

  await fakeModel();
  await page.getByRole('link', { name: 'Documents', exact: true }).click();
  await expect(documentRow(page, 'contract.pdf').getByText('Ready', { exact: true })).toBeVisible();
  await openAsk(page);
});

test('a question gets an answer with its source, which opens at the cited passage', async ({ page, context }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);

  const turn = await ask(page, NOTICE_QUESTION);

  await expect(turn.getByTestId('turn-question')).toHaveText(NOTICE_QUESTION);
  await expect(turn.getByTestId('answer')).toContainText('notice period of 60 days');
  await expect(page).toHaveURL(/\/ask\/[0-9a-f-]{36}$/);
  const citation = turn.getByTestId('citation').first();
  await expect(citation).toContainText('contract.pdf');
  await expect(citation).toContainText('page 2');
  await expect(page.getByTestId('question')).toHaveValue('');

  await turn.getByTestId('view-page').first().click();

  await expect(page.getByTestId('page-position')).toContainText('2');
  await expect(page.getByTestId('page-highlight')).toContainText('notice period of 60 days');

  const original = page.getByTestId('open-original');
  const href = await original.getAttribute('href');
  expect(href).toMatch(/\/file#page=2$/);
  const file = await context.request.get(href!.split('#')[0]);
  expect(file.status()).toBe(200);
  expect(file.headers()['content-type']).toBe('application/pdf');
  expect((await file.body()).equals(CONTRACT)).toBe(true);

  await page.getByRole('button', { name: 'Close' }).click();
  await expect(page.getByTestId('page-highlight')).toHaveCount(0);
});

test('a follow-up shows what was searched for', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await ask(page, NOTICE_QUESTION);

  await fakeModel({ rewriteReply: 'What is the penalty for terminating the contract without notice?' });
  const followUp = await ask(page, 'And the penalty?');

  await expect(followUp.getByTestId('interpreted')).toContainText('What is the penalty for terminating');
  await expect(followUp.getByTestId('citation').first()).toContainText('page 2');
  await expect(page.getByTestId('turn')).toHaveCount(2);
});

test('a question the documents cannot answer says so, without asking the model', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await fakeModel();

  const turn = await ask(page, 'Who painted Guernica?');

  await expect(turn.getByTestId('not-found')).toBeVisible();
  await expect(turn.getByTestId('answer')).toHaveCount(0);
  expect((await fakeModelCalls()).chat).toBe(0);
});

test('a question can be limited to one document', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await uploadAndWait(page, 'handbook.pdf', HANDBOOK);
  await openAsk(page);

  await page.getByTestId('scope').click();
  await page.locator('mat-option', { hasText: 'handbook.pdf' }).click();
  const turn = await ask(page, NOTICE_QUESTION);

  await expect(turn.getByTestId('citation').filter({ hasText: 'contract.pdf' })).toHaveCount(0);
});

test('clicking twice sends a question only once', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await fakeModel({ chatDelayMs: 1500 });

  await page.getByTestId('question').fill(NOTICE_QUESTION);
  await page.locator('form').getByRole('button', { name: 'Ask' }).dblclick();

  await expect(page.getByTestId('turn')).toHaveCount(1);
  await page.waitForTimeout(2000);
  await expect(page.getByTestId('turn')).toHaveCount(1);
  expect((await fakeModelCalls()).chat).toBe(1);
});

test('conversations are kept and open again after signing out', async ({ page }) => {
  const email = await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await ask(page, NOTICE_QUESTION);
  const address = page.url();
  await page.getByTestId('new-conversation').click();
  await expect(page).toHaveURL(/\/ask$/);
  await ask(page, 'What is the monthly fee?');

  await expect(page.getByTestId('conversation')).toHaveCount(2);
  await expect(page.getByTestId('conversation').first()).toContainText('monthly fee');

  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.goto(address);
  await expect(page).toHaveURL(/\/login/);
  await signIn(page, email);

  await expect(page).toHaveURL(address);
  await expect(page.getByTestId('turn-question')).toHaveText(NOTICE_QUESTION);
  await expect(page.getByTestId('conversation')).toHaveCount(2);

  await page.reload();
  await expect(page.getByTestId('turn-question')).toHaveText(NOTICE_QUESTION);
  await expect(page.getByTestId('answer')).toContainText('60 days');
});

test('deleting a document keeps the answer and says that its source is gone', async ({ page, context }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  const fileLink = await documentRow(page, 'contract.pdf').getByTestId('open-file').getAttribute('href');
  await openAsk(page);
  const turn = await ask(page, NOTICE_QUESTION);
  await expect(turn.getByTestId('view-page').first()).toBeVisible();
  const address = page.url();

  await page.getByRole('link', { name: 'Documents', exact: true }).click();
  await page.getByRole('button', { name: 'Delete contract.pdf' }).click();
  await expect(page.getByTestId('confirm-message')).toContainText('Conversations that used it stay');
  await confirmDeletion(page);
  await expect(documentRow(page, 'contract.pdf')).toHaveCount(0);
  expect((await context.request.get(fileLink!)).status()).toBe(404);

  await page.goto(address);
  await expect(page.getByTestId('answer')).toContainText('60 days');
  await expect(page.getByTestId('source-deleted')).toBeVisible();
  await expect(page.getByTestId('view-page')).toHaveCount(0);
});

test('a document deleted by mistake can be cancelled at the confirmation', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);

  await page.getByRole('button', { name: 'Delete contract.pdf' }).click();
  await page.locator('mat-dialog-actions').getByRole('button', { name: 'Cancel' }).click();

  await expect(documentRow(page, 'contract.pdf')).toBeVisible();
});

test('deleting a conversation removes it from the history and leaves the documents', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await ask(page, NOTICE_QUESTION);
  const address = page.url();

  await page.getByTestId('delete-conversation').click();
  await confirmDeletion(page);

  await expect(page).toHaveURL(/\/ask$/);
  await expect(page.getByTestId('conversation')).toHaveCount(0);
  await page.goto(address);
  await expect(page.getByTestId('conversation-error')).toContainText('not found');

  await page.getByRole('link', { name: 'Documents', exact: true }).click();
  await expect(documentRow(page, 'contract.pdf')).toBeVisible();
});

test('a question can be sent with the Enter key, and Shift+Enter starts a new line', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  const field = page.getByTestId('question');

  await field.fill('What is the monthly');
  await field.press('Shift+Enter');
  await expect(page.getByTestId('turn')).toHaveCount(0);
  await field.pressSequentially('fee?');
  await expect(field).toHaveValue('What is the monthly\nfee?');

  await field.press('Enter');

  await expect(page.getByTestId('turn')).toHaveCount(1);
});

test('an empty question is not sent', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await fakeModel();

  await sendQuestion(page, '   ');

  await expect(page.getByTestId('turn')).toHaveCount(0);
  await expect(page.getByTestId('pending')).toHaveCount(0);
  expect((await fakeModelCalls()).chat).toBe(0);
});
