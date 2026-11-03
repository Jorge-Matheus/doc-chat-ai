import {
  ask,
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
import { CONTRACT } from '../support/pdfs';
import { restartService, sql, startService, stopService, whileStopped } from '../support/stack';

const QUESTION = 'What is the notice period to terminate the contract?';
const READINESS_URL = 'http://localhost:18080/actuator/health/readiness';
const LIVENESS_URL = 'http://localhost:18080/actuator/health/liveness';

test.beforeEach(async () => {
  await fakeModel();
});

test.afterEach(async () => {
  await fakeModel();
});

test.describe('the AI service', () => {
  test('when it stops, questions explain it and work again when it returns', async ({ page }) => {
    await register(page);
    await uploadAndWait(page, 'contract.pdf', CONTRACT);
    await openAsk(page);

    await whileStopped('fake-ollama', async () => {
      await sendQuestion(page, QUESTION);

      await expect(page.getByTestId('ask-error')).toContainText('AI service is not available');
      await expect(page.getByTestId('question')).toHaveValue(QUESTION);
      await expect(page.getByTestId('turn')).toHaveCount(0);

      expect((await page.request.get(READINESS_URL)).status(), 'accounts and documents still work').toBe(200);
      await page.goto('/');
      await expect(page.getByTestId('status-row').filter({ hasText: 'AI models' })).toContainText('Down');
      await expect(page.getByTestId('status-degraded')).toBeVisible();
      await page.goto('/ask');
    });

    await page.goto('/');
    await expect(page.getByTestId('status-row').filter({ hasText: 'AI models' })).toContainText('Up');
    await page.getByRole('link', { name: 'Ask', exact: true }).click();
    const turn = await ask(page, QUESTION);
    await expect(turn.getByTestId('answer')).toContainText('60 days');
  });

  test('a document that failed because of it works when tried again', async ({ page }) => {
    await register(page);
    await fakeModel({ failEmbed: true });

    await upload(page, 'contract.pdf', CONTRACT);

    const row = documentRow(page, 'contract.pdf');
    await expect(row.getByTestId('document-failure')).toContainText('AI service was not available');
    await expect(row.getByText('Failed', { exact: true })).toBeVisible();

    await fakeModel();
    await row.getByTestId('retry').click();

    await expect(row.getByText('Ready', { exact: true })).toBeVisible();
    await expect(row.getByTestId('document-failure')).toHaveCount(0);
    await expect(row.getByTestId('retry')).toHaveCount(0);
    await openAsk(page);
    await expect((await ask(page, QUESTION)).getByTestId('answer')).toContainText('60 days');
  });

  test('a failure in the model answers with a message and stores nothing', async ({ page }) => {
    await register(page);
    await uploadAndWait(page, 'contract.pdf', CONTRACT);
    await openAsk(page);
    await fakeModel({ failChat: true });

    await sendQuestion(page, QUESTION);

    await expect(page.getByTestId('ask-error')).toContainText('AI service is not available');
    await expect(page.getByTestId('question')).toHaveValue(QUESTION);
    await page.reload();
    await expect(page.getByTestId('conversation')).toHaveCount(0);
    await expect(page.getByTestId('history-empty')).toBeVisible();
  });

  test('a model too slow for the time limit gets a message, and nothing is stored', async ({ page }) => {
    await register(page);
    await uploadAndWait(page, 'contract.pdf', CONTRACT);
    await openAsk(page);
    await fakeModel({ chatDelayMs: 60_000 });
    const started = Date.now();

    await sendQuestion(page, QUESTION);
    await expect(page.getByTestId('pending')).toBeVisible();
    await expect(page.getByTestId('ask-error')).toContainText('AI service is not available');

    expect(Date.now() - started, 'answers at the time limit of the test stack').toBeLessThan(20_000);
    await expect(page.getByTestId('question')).toHaveValue(QUESTION);
    await page.reload();
    await expect(page.getByTestId('history-empty')).toBeVisible();

    // The slot of the abandoned question is free again, so the next one is answered.
    await fakeModel();
    await expect((await ask(page, QUESTION)).getByTestId('answer')).toContainText('60 days');
  });

  test('a question beyond the limit is refused with a message, and the first is still answered', async ({
    page,
    context,
  }) => {
    await register(page);
    await uploadAndWait(page, 'contract.pdf', CONTRACT);
    await openAsk(page);
    const second = await context.newPage();
    await second.goto('/ask');
    await expect(second.getByTestId('question')).toBeVisible();
    await fakeModel({ chatDelayMs: 4000 });

    await sendQuestion(page, QUESTION);
    await expect(page.getByTestId('pending')).toBeVisible();
    await sendQuestion(second, 'What is the monthly fee?');

    await expect(second.getByTestId('ask-error')).toContainText('Several questions are being answered');
    await expect(second.getByTestId('question')).toHaveValue('What is the monthly fee?');
    await expect(page.getByTestId('turn')).toHaveCount(1);
    expect((await fakeModelCalls()).chat).toBe(1);
    await second.close();
  });

  test('a document past the time limit fails with its reason and can be tried again', async ({ page }) => {
    await register(page);
    await fakeModel({ embedDelayMs: 60_000 });

    await upload(page, 'slow.pdf', CONTRACT);

    const row = documentRow(page, 'slow.pdf');
    await expect(row.getByText('Processing', { exact: true })).toBeVisible();
    await expect(row.getByTestId('document-failure')).toContainText('took too long', { timeout: 40_000 });

    await fakeModel();
    await row.getByTestId('retry').click();
    await expect(row.getByText('Ready', { exact: true })).toBeVisible();
  });
});

test.describe('the backend', () => {
  test('restarting it during processing finishes the document, and the page recovers by itself', async ({ page }) => {
    await register(page);
    await fakeModel({ embedDelayMs: 8000 });
    await upload(page, 'contract.pdf', CONTRACT);
    const row = documentRow(page, 'contract.pdf');
    await expect(row.getByText('Processing', { exact: true })).toBeVisible();
    await expect.poll(async () => (await fakeModelCalls()).embed, 'the model is working on it').toBeGreaterThan(0);

    await fakeModel();
    restartService('backend');

    await expect(row.getByText('Ready', { exact: true })).toBeVisible({ timeout: 90_000 });
    await expect(row.getByTestId('document-failure')).toHaveCount(0);
    await expect(page.getByTestId('load-error')).toHaveCount(0);

    // The session is kept in the database, so the user stays signed in.
    await page.reload();
    await expect(page.getByTestId('user-email')).toBeVisible();
    await expect(row.getByText('Ready', { exact: true })).toBeVisible();
    await openAsk(page);
    await expect((await ask(page, QUESTION)).getByTestId('answer')).toContainText('60 days');
  });

  test('when it stops, the pages say so and recover when it returns', async ({ page }) => {
    await register(page);
    await uploadAndWait(page, 'contract.pdf', CONTRACT);
    await openAsk(page);
    await page.getByTestId('question').fill(QUESTION);

    await whileStopped('backend', async () => {
      await page.locator('form').getByRole('button', { name: 'Ask' }).click();
      await expect(page.getByTestId('ask-error')).toContainText('could not be answered');
      await expect(page.getByTestId('question')).toHaveValue(QUESTION);

      await page.goto('/');
      await expect(page.getByTestId('status-unreachable')).toBeVisible();
    });

    await page.reload();
    await expect(page.getByTestId('status-row').filter({ hasText: 'Backend' })).toContainText('Up');
    await page.getByRole('link', { name: 'Ask', exact: true }).click();
    await expect((await ask(page, QUESTION)).getByTestId('answer')).toContainText('60 days');
  });

  test('the checks of a processing document survive it stopping for a moment', async ({ page }) => {
    await register(page);
    await fakeModel({ embedDelayMs: 6000 });
    await upload(page, 'contract.pdf', CONTRACT);
    const row = documentRow(page, 'contract.pdf');
    await expect(row.getByText('Processing', { exact: true })).toBeVisible();

    await whileStopped('backend', async () => {
      await expect(page.getByTestId('load-error')).toBeVisible();
      await expect(row).toBeVisible();
      await fakeModel();
    });

    await expect(row.getByText('Ready', { exact: true })).toBeVisible();
    await expect(page.getByTestId('load-error')).toHaveCount(0);
  });
});

test.describe('the database', () => {
  test('when it stops, a signed-in browser sees the real state, and everything recovers when it returns', async ({
    page,
    browser,
  }) => {
    const email = await register(page);
    await uploadAndWait(page, 'contract.pdf', CONTRACT);
    const someoneElse = await browser.newContext({ baseURL: 'http://localhost:4300' });
    const login = await someoneElse.newPage();

    await whileStopped('db', async () => {
      // These requests carry the session cookie, and sessions are in the database that is down.
      expect((await page.request.get(READINESS_URL)).status()).toBe(503);
      expect((await page.request.get(LIVENESS_URL)).status()).toBe(200);
      const status = await page.request.get('/api/system/status');
      expect(status.status()).toBe(200);
      expect(((await status.json()) as { database: string }).database).toBe('DOWN');

      const documents = await page.request.get('/api/documents');
      expect(documents.status()).toBe(503);
      expect(((await documents.json()) as { code: string }).code).toBe('DATABASE_UNAVAILABLE');

      await page.goto('/');
      await expect(page.getByTestId('status-row').filter({ hasText: 'Database' })).toContainText('Down');
      await expect(page.getByTestId('status-degraded')).toBeVisible();

      await login.goto('/login');
      await signIn(login, email);
      await expect(login.getByTestId('form-error')).toContainText('database is not available');
    });

    await someoneElse.close();
    await page.goto('/documents');
    await expect(page.getByTestId('user-email')).toHaveText(email);
    await expect(documentRow(page, 'contract.pdf').getByText('Ready', { exact: true })).toBeVisible();
  });

  for (const outageMs of [1000, 3000, 6000]) {
    test(`a document being processed when it stops for ${outageMs} ms is finished after it returns`, async ({
      page,
    }) => {
      await register(page);
      await fakeModel({ embedDelayMs: 4000 });
      await upload(page, 'contract.pdf', CONTRACT);
      const row = documentRow(page, 'contract.pdf');
      await expect(row.getByText('Processing', { exact: true })).toBeVisible();
      await expect.poll(async () => (await fakeModelCalls()).embed, 'the model is working on it').toBeGreaterThan(0);

      // How long it stays down decides how saving fails, and each way has to leave the document as it was.
      stopService('db');
      await page.waitForTimeout(outageMs);
      await fakeModel();
      startService('db');

      await expect(row.getByText('Ready', { exact: true })).toBeVisible({ timeout: 60_000 });
      await expect(row.getByTestId('document-failure')).toHaveCount(0);
      expect(sql("select count(*) from documents where status = 'PROCESSING'")).toBe('0');
    });
  }
});
