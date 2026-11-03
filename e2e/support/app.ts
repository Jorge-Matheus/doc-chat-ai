import { type BrowserContext, type Locator, type Page, expect, test as base } from '@playwright/test';

export const PASSWORD = 'a long enough password';

let emailCounter = 0;

/** Each test has its own accounts, so the tests do not see each other's data. */
export function uniqueEmail(prefix = 'user'): string {
  emailCounter += 1;
  return `${prefix}-${Date.now()}-${emailCounter}@example.com`;
}

/** Fails any test where the page throws an error or the browser blocks something under the CSP. */
export const test = base.extend<{ problems: string[] }>({
  problems: [
    async ({ context }, use) => {
      const problems: string[] = [];
      context.on('page', (page) => watchForProblems(page, problems));
      await use(problems);
      expect(problems, 'errors in the page or blocked by the Content Security Policy').toEqual([]);
    },
    { auto: true },
  ],
});

function watchForProblems(page: Page, problems: string[]): void {
  page.on('pageerror', (error) => problems.push(`Page error: ${error.message}`));
  page.on('console', (message) => {
    // A failed request logs an error by itself, and many tests cause those on purpose.
    if (message.type() === 'error' && /Content Security Policy|Refused to/i.test(message.text())) {
      problems.push(`Blocked: ${message.text()}`);
    }
  });
}

export { expect };

export async function register(page: Page, email = uniqueEmail()): Promise<string> {
  await page.goto('/register');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await page.waitForURL(/\/documents$/);
  return email;
}

export async function signIn(page: Page, email: string): Promise<void> {
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.locator('form').getByRole('button', { name: 'Sign in' }).click();
}

export function documentRow(page: Page, filename: string): Locator {
  return page.getByTestId('document').filter({ hasText: filename });
}

export async function chooseFile(page: Page, name: string, buffer: Buffer, mimeType = 'application/pdf'): Promise<void> {
  await page.getByTestId('file-input').setInputFiles({ name, mimeType, buffer });
}

export async function upload(page: Page, name: string, buffer: Buffer): Promise<void> {
  await chooseFile(page, name, buffer);
  await expect(documentRow(page, name)).toBeVisible();
}

export async function uploadAndWait(page: Page, name: string, buffer: Buffer): Promise<void> {
  await upload(page, name, buffer);
  await expect(documentRow(page, name).getByText('Ready', { exact: true })).toBeVisible();
}

export async function openAsk(page: Page): Promise<void> {
  await page.getByRole('link', { name: 'Ask', exact: true }).click();
  await expect(page.getByTestId('question')).toBeVisible();
}

export async function sendQuestion(page: Page, question: string): Promise<void> {
  await page.getByTestId('question').fill(question);
  await page.locator('form').getByRole('button', { name: 'Ask' }).click();
}

/** Asks in the open conversation, and waits for the answer or the error. */
export async function ask(page: Page, question: string): Promise<Locator> {
  const turns = page.getByTestId('turn');
  const before = await turns.count();
  await sendQuestion(page, question);
  await expect(turns.nth(before).or(page.getByTestId('ask-error')).first()).toBeVisible();
  await expect(page.getByTestId('pending')).toHaveCount(0);
  return turns.nth(before);
}

export async function confirmDeletion(page: Page): Promise<void> {
  await page.locator('mat-dialog-actions').getByRole('button', { name: 'Delete' }).click();
}

/** The token a real request sends, for tests that talk to the API directly from a signed-in browser. */
export async function csrfToken(context: BrowserContext): Promise<string> {
  const cookies = await context.cookies();
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN');
  if (!token) {
    throw new Error('The browser has no XSRF-TOKEN cookie');
  }
  return token.value;
}

const FAKE_MODEL_URL = 'http://127.0.0.1:11535';

export interface FakeModelBehaviour {
  chatDelayMs?: number;
  embedDelayMs?: number;
  failChat?: boolean;
  failEmbed?: boolean;
  reply?: string;
  rewriteReply?: string;
}

/** Replaces the whole behaviour, so with no arguments the model is fast and working again. */
export async function fakeModel(behaviour: FakeModelBehaviour = {}): Promise<void> {
  const response = await fetch(`${FAKE_MODEL_URL}/__control`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(behaviour),
  });
  expect(response.ok).toBe(true);
}

export async function fakeModelCalls(): Promise<{ chat: number; embed: number }> {
  return (await (await fetch(`${FAKE_MODEL_URL}/__calls`)).json()) as { chat: number; embed: number };
}
