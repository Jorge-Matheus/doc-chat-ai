import { ask, documentRow, expect, fakeModel, openAsk, register, test, uploadAndWait } from '../support/app';
import { CONTRACT, pdf } from '../support/pdfs';

/** Would set a global variable if the browser ever ran it as markup. */
const ATTACK = '<img src=x onerror="window.attacked=true"><script>window.attacked=true</script>';
const FILENAME = '<img src=x onerror=window.attacked=true>.pdf';

const wasAttacked = (page: import('@playwright/test').Page): Promise<boolean> =>
  page.evaluate(() => 'attacked' in window);
const injectedElements = (page: import('@playwright/test').Page) => page.locator('img[src="x"], main script');

test.beforeEach(async ({ page }) => {
  await fakeModel();
  await register(page);
});

test('markup in a file name, a document, a question and an answer is only shown as text', async ({ page }) => {
  await uploadAndWait(page, FILENAME, pdf([`contract clause ${ATTACK}`]));
  await expect(documentRow(page, FILENAME)).toContainText('<img src=x');
  expect(await wasAttacked(page)).toBe(false);
  await expect(injectedElements(page)).toHaveCount(0);

  await openAsk(page);
  await fakeModel({ reply: `The clause says ${ATTACK} [1].` });
  const turn = await ask(page, `contract clause ${ATTACK}`);

  await expect(turn.getByTestId('turn-question')).toContainText('<script>');
  await expect(turn.getByTestId('answer')).toContainText('<script>');
  await expect(page.getByTestId('conversation').first()).toContainText('<img src=x');
  await expect(injectedElements(page)).toHaveCount(0);

  await turn.getByTestId('view-page').first().click();
  await expect(page.getByTestId('page-text')).toContainText('<script>');
  await expect(injectedElements(page)).toHaveCount(0);
  await page.getByRole('button', { name: 'Close' }).click();

  await page.reload();
  await expect(page.getByTestId('answer')).toContainText('<script>');
  await expect(injectedElements(page)).toHaveCount(0);
  expect(await wasAttacked(page)).toBe(false);
});

test('a script added to the page by any other way is blocked by the Content Security Policy', async ({
  page,
  problems,
}) => {
  const ran = await page.evaluate(
    () =>
      new Promise<boolean>((resolve) => {
        const script = document.createElement('script');
        script.textContent = 'window.attacked = true;';
        document.body.append(script);
        setTimeout(() => resolve('attacked' in window), 200);
      }),
  );

  expect(ran).toBe(false);
  // This one is on purpose, so it must not fail the test.
  expect(problems.filter((problem) => problem.includes('Content Security Policy'))).toHaveLength(1);
  problems.length = 0;
});

test('an answer citing a source it was not given loses that citation', async ({ page }) => {
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await fakeModel({ reply: 'The notice is 60 days [1]. The contract was signed on Mars [9].' });

  const turn = await ask(page, 'What is the notice period to terminate the contract?');

  await expect(turn.getByTestId('citation')).toHaveCount(1);
  await expect(turn.getByTestId('answer')).not.toContainText('[9]');
});

test('an answer with no valid citation is not shown', async ({ page }) => {
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await fakeModel({ reply: 'The contract was signed on Mars [9].' });

  const turn = await ask(page, 'What is the notice period to terminate the contract?');

  await expect(turn.getByTestId('not-found')).toBeVisible();
  await expect(turn.getByText('Mars')).toHaveCount(0);
});

test('an answer without any citation is not shown', async ({ page }) => {
  await uploadAndWait(page, 'contract.pdf', CONTRACT);
  await openAsk(page);
  await fakeModel({ reply: 'HACKED' });

  const turn = await ask(page, 'What is the notice period to terminate the contract?');

  await expect(turn.getByTestId('not-found')).toBeVisible();
  await expect(turn.getByText('HACKED')).toHaveCount(0);
});

test('the original file of a document that is also a web page is never run as a page', async ({ page, context }) => {
  const polyglot = Buffer.concat([
    Buffer.from(`<html><script>window.attacked=true</script></html>\n`),
    pdf(['This file is a web page and a PDF at the same time.']),
  ]);
  await uploadAndWait(page, 'polyglot.pdf', polyglot);
  const link = await page.getByTestId('open-file').getAttribute('href');

  const file = await context.request.get(link!);

  expect(file.headers()['content-type']).toBe('application/pdf');
  expect(file.headers()['x-content-type-options']).toBe('nosniff');
});
