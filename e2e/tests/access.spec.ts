import {
  PASSWORD,
  ask,
  csrfToken,
  expect,
  fakeModel,
  openAsk,
  register,
  signIn,
  test,
  uniqueEmail,
  uploadAndWait,
} from '../support/app';
import { CONTRACT } from '../support/pdfs';

test.beforeEach(async () => {
  await fakeModel();
});

test('someone not signed in is sent to sign in, and then back to where they were going', async ({ page }) => {
  const email = await register(page);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByTestId('user-email')).toHaveCount(0);

  for (const path of ['/documents', '/ask', '/ask/6c5baffe-63a2-4bb0-a913-a55c3eb89a07']) {
    await page.goto(path);
    await expect(page, path).toHaveURL(/\/login\?returnUrl=/);
  }

  await page.goto('/documents');
  await signIn(page, email);
  await expect(page).toHaveURL(/\/documents$/);
});

test("another account's conversation, page and file are not reachable, even with their addresses", async ({
  page,
  browser,
}) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  const fileLink = await page.getByTestId('open-file').getAttribute('href');
  const documentId = fileLink!.split('/')[3];
  await openAsk(page);
  await ask(page, 'What is the notice period to terminate the contract?');
  const conversationAddress = new URL(page.url()).pathname;

  const otherContext = await browser.newContext({ baseURL: 'http://localhost:4300' });
  const other = await otherContext.newPage();
  await register(other);

  await other.goto(conversationAddress);
  await expect(other.getByTestId('conversation-error')).toContainText('not found');
  await expect(other.getByTestId('answer')).toHaveCount(0);

  const token = await csrfToken(otherContext);
  const headers = { 'X-XSRF-TOKEN': token };
  const api = otherContext.request;
  expect((await api.get(fileLink!)).status()).toBe(404);
  expect((await api.get(`/api/documents/${documentId}`)).status()).toBe(404);
  expect((await api.get(`/api/documents/${documentId}/pages/1`)).status()).toBe(404);
  expect((await api.delete(`/api/documents/${documentId}`, { headers })).status()).toBe(404);
  expect((await api.post(`/api/documents/${documentId}/retry`, { headers })).status()).toBe(404);
  expect((await api.get(`/api/conversations${conversationAddress.replace('/ask', '')}`)).status()).toBe(404);

  // Naming the other user's document in a question does not reach it either.
  await other.getByRole('link', { name: 'Documents', exact: true }).click();
  const question = await api.post('/api/conversations', {
    headers,
    data: { question: 'What is the notice period to terminate the contract?', documentIds: [documentId] },
  });
  expect(question.status()).toBe(201);
  const conversation = (await question.json()) as { turns: { answered: boolean; citations: unknown[] }[] };
  expect(conversation.turns[0].answered).toBe(false);
  expect(conversation.turns[0].citations).toEqual([]);

  await otherContext.close();

  await page.reload();
  await expect(page.getByTestId('answer')).toContainText('60 days');
  await page.getByRole('link', { name: 'Documents', exact: true }).click();
  await expect(page.getByTestId('document')).toHaveCount(1);
});

test('when the session ends, the next action leads to signing in and nothing is lost', async ({ page, context }) => {
  const email = await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await page.getByRole('link', { name: 'Ask', exact: true }).click();
  await expect(page.getByTestId('question')).toBeVisible();

  await context.clearCookies({ name: 'SESSION' });
  await page.getByTestId('question').fill('What is the monthly fee?');
  await page.locator('form').getByRole('button', { name: 'Ask' }).click();

  await expect(page).toHaveURL(/\/login\?returnUrl=/);
  await expect(page.getByTestId('user-email')).toHaveCount(0);

  await signIn(page, email);
  await expect(page).toHaveURL(/\/ask/);
  await expect(page.getByTestId('question')).toBeVisible();
});

test('a wrong password and an unknown email get the same answer', async ({ page }) => {
  const email = await register(page);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByTestId('user-email')).toHaveCount(0);

  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('this is not the right one');
  await page.locator('form').getByRole('button', { name: 'Sign in' }).click();
  const wrongPassword = await page.getByTestId('form-error').textContent();

  await page.getByLabel('Email').fill(uniqueEmail('nobody'));
  await page.getByLabel('Password').fill(PASSWORD);
  await page.locator('form').getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByTestId('form-error')).toHaveText(wrongPassword!);
  await expect(page).toHaveURL(/\/login/);
});

test('registering the same email twice is refused and the first account keeps working', async ({ page }) => {
  const email = await register(page);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByTestId('user-email')).toHaveCount(0);

  await page.goto('/register');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('some other long password');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByTestId('form-error')).toBeVisible();

  await page.goto('/login');
  await signIn(page, email);
  await expect(page.getByTestId('user-email')).toHaveText(email);
});

test('a request forged without the token is refused, from inside a signed-in browser', async ({ page }) => {
  await register(page);
  await uploadAndWait(page, 'contract.pdf', CONTRACT);

  const status = await page.evaluate(async () => {
    const list = (await (await fetch('/api/documents')).json()) as { items: { id: string }[] };
    const response = await fetch(`/api/documents/${list.items[0].id}`, { method: 'DELETE' });
    return response.status;
  });

  expect(status).toBe(403);
  await page.reload();
  await expect(page.getByTestId('document')).toHaveCount(1);
});

test('the session cookie cannot be read by scripts in the page', async ({ page, context }) => {
  await register(page);

  const cookies = await context.cookies();
  expect(cookies.find((cookie) => cookie.name === 'SESSION')?.httpOnly).toBe(true);
  expect(cookies.find((cookie) => cookie.name === 'SESSION')?.sameSite).toBe('Lax');
  expect(await page.evaluate(() => document.cookie)).not.toContain('SESSION');
});

test('the pages carry the security headers, and internal endpoints are not forwarded', async ({ request }) => {
  const page = await request.get('/');
  const headers = page.headers();
  expect(headers['content-security-policy']).toContain("script-src 'self'");
  expect(headers['content-security-policy']).toContain("frame-ancestors 'none'");
  expect(headers['x-frame-options']).toBe('DENY');
  expect(headers['x-content-type-options']).toBe('nosniff');
  expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');

  const api = await request.get('/api/system/status');
  expect(api.headers()['x-content-type-options']).toBe('nosniff');
  expect(api.headers()['x-frame-options']).toBe('DENY');

  const actuator = await request.get('/actuator/env');
  expect(await actuator.text()).toContain('<app-root');
});
