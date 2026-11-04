import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ask, expect, fakeModel, openAsk, register, test, uploadAndWait } from '../support/app';
import { pdf } from '../support/pdfs';
import { E2E_ROOT } from '../support/stack';

const METRICS_URL = 'http://localhost:18080/actuator/prometheus';
const DASHBOARD = join(E2E_ROOT, '..', 'observability', 'grafana', 'dashboards', 'docchat.json');

interface Panel {
  title: string;
  targets?: { expr: string }[];
}

/** The names of the metrics used in a query, leaving out functions, labels and their values. */
function metricsIn(query: string): string[] {
  const withoutLabels = query.replace(/\{[^}]*}/g, '').replace(/by \([^)]*\)/g, '').replace(/\[[^\]]*]/g, '');
  return (withoutLabels.match(/[a-z][a-z0-9_]*/g) ?? []).filter(
    (word) => !['histogram_quantile', 'sum', 'rate', 'topk', 'increase'].includes(word),
  );
}

test('every panel of the dashboard reads metrics the application really exposes', async ({ page, request }) => {
  await fakeModel();
  await register(page);
  await uploadAndWait(page, 'contract.pdf', pdf(['The notice period to terminate the contract is 60 days.']));
  await openAsk(page);
  await ask(page, 'What is the notice period to terminate the contract?');
  await ask(page, 'And the notice period of the contract?');

  const metrics = await (await request.get(METRICS_URL)).text();
  const panels = (JSON.parse(readFileSync(DASHBOARD, 'utf8')) as { panels: Panel[] }).panels;
  const queries = panels.flatMap((panel) => (panel.targets ?? []).map((target) => ({ panel: panel.title, query: target.expr })));

  expect(queries.length).toBeGreaterThan(15);
  for (const { panel, query } of queries) {
    const names = metricsIn(query);
    expect(names.length, `${panel}: ${query}`).toBeGreaterThan(0);
    for (const name of names) {
      expect(metrics, `"${panel}" reads ${name}`).toMatch(new RegExp(`^${name}[{ ]`, 'm'));
    }
  }
});

test('the metrics hold no user data, and the proxy does not forward them', async ({ page, request }) => {
  await fakeModel({ reply: 'SecretAnswerWord [1].' });
  const email = await register(page);
  await uploadAndWait(page, 'SecretFileName.pdf', pdf(['SecretDocumentWord is in this contract.']));
  await openAsk(page);
  await ask(page, 'SecretDocumentWord SecretQuestionWord?');

  const metrics = await (await request.get(METRICS_URL)).text();

  for (const secret of [email, 'SecretFileName', 'SecretDocumentWord', 'SecretQuestionWord', 'SecretAnswerWord']) {
    expect(metrics).not.toContain(secret);
  }
  expect(metrics).toContain('gen_ai_client_token_usage_total');

  const throughTheProxy = await request.get('/actuator/prometheus');
  expect(await throughTheProxy.text()).toContain('<app-root');
});
