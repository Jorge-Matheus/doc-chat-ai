// Run through npm run load, which starts the stack and reports the results.
//
// MODE=simulated (the default) uses the fake model, which answers at once. It measures the application
// under several users at once, and is repeatable, because the time and cost of a real model would hide
// everything else. MODE=real asks the real models one question at a time, to measure what a person
// waits for. MODE=scale fills one account with the largest documents allowed and asks one question at
// a time, because searching compares the question with every passage of the account.
import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const MODE = __ENV.MODE || 'simulated';
const DURATION = __ENV.DURATION || '3m';
const PASSWORD = 'a long enough password';
const ACCOUNTS = 10;
const PAGES_PER_DOCUMENT = 40;
const LARGEST_DOCUMENT_PAGES = 2000;
const SCALE_DOCUMENTS = Number(__ENV.SCALE_DOCUMENTS || 5);

const documentReadyTime = new Trend('document_ready_time', true);
const questionsRefused = new Counter('questions_refused');

const SIMULATED = {
  // People reading their documents and conversations.
  browsing: { executor: 'constant-vus', vus: 20, duration: DURATION, exec: 'browse' },
  // More questions per second than one person asks, up to the limit of those answered at once.
  asking: {
    executor: 'constant-arrival-rate',
    rate: 2,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: 10,
    maxVUs: 20,
    exec: 'askQuestion',
  },
  // A new document every 10 seconds, followed until it is ready, then deleted.
  uploading: {
    executor: 'constant-arrival-rate',
    rate: 6,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: 3,
    maxVUs: 6,
    exec: 'uploadDocument',
  },
};

const ONE_AT_A_TIME = {
  asking: { executor: 'per-vu-iterations', vus: 1, iterations: 100, maxDuration: '30m', exec: 'askQuestion' },
};

const SIMULATED_THRESHOLDS = {
  checks: ['rate>0.99'],
  http_req_failed: ['rate<0.01'],
  'http_req_duration{name:list documents}': ['p(95)<500'],
  'http_req_duration{name:list conversations}': ['p(95)<500'],
  'http_req_duration{name:read page}': ['p(95)<500'],
  'http_req_duration{name:ask}': ['p(95)<2000'],
  'http_req_duration{name:upload}': ['p(95)<2000'],
  document_ready_time: ['p(95)<10000'],
};

export const options = {
  setupTimeout: '60m',
  // A browser keeps its session cookie between actions, and k6 would clear it on each iteration.
  noCookiesReset: true,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
  scenarios: MODE === 'simulated' ? SIMULATED : ONE_AT_A_TIME,
  // k6 only reports a group of requests that has a threshold, and these runs have no target to meet.
  thresholds: MODE === 'simulated' ? SIMULATED_THRESHOLDS : { checks: ['rate>0.99'], 'http_req_duration{name:ask}': ['max>=0'] },
};

/** Builds a real PDF, as the browser tests do, because this runtime has no libraries for it. */
function pdf(pageTexts) {
  const kids = pageTexts.map((_, index) => `${4 + 2 * index} 0 R`).join(' ');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Kids [${kids}] /Count ${pageTexts.length} >>`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  pageTexts.forEach((text, index) => {
    const content = `BT /F1 10 Tf 40 760 Td (${text}) Tj ET`;
    objects.push(
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + 2 * index} 0 R >>`,
      `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    );
  });
  let file = '%PDF-1.7\n';
  const offsets = objects.map((body, index) => {
    const offset = file.length;
    file += `${index + 1} 0 obj\n${body}\nendobj\n`;
    return offset;
  });
  const xref = file.length;
  file += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((offset) => (file += `${String(offset).padStart(10, '0')} 00000 n \n`));
  return `${file}trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
}

const pageText = (number) =>
  `Clause ${number}. The notice period of clause ${number} is ${number + 10} days, and the penalty of clause ${number} is ${number} percent of the monthly fee.`;

const SIMULATED_DOCUMENT = pdf(Array.from({ length: PAGES_PER_DOCUMENT }, (_, index) => pageText(index + 1)));
const SMALL_DOCUMENT = pdf([pageText(1), pageText(2), pageText(3)]);
const largestDocument = () => pdf(Array.from({ length: LARGEST_DOCUMENT_PAGES }, (_, index) => pageText(index + 1)));
const REAL_DOCUMENT = pdf([
  'Service agreement between the provider and the customer for the use of the Orchid management system.',
  'Clause 7. The contract can be terminated by either party with a notice period of 60 days. Termination without notice incurs a penalty of 20 percent of the remaining fees.',
  'Clause 9. The monthly fee is 4500 reais, adjusted every year, and it is due on day 10 of each month.',
  'Clause 12. Any dispute about this contract goes to the courts of the city of Curitiba.',
]);
const REAL_QUESTIONS = [
  'What is the notice period to terminate the contract?',
  'What is the penalty for terminating without notice?',
  'How much is the monthly fee?',
  'Which courts handle disputes about this contract?',
  'Who painted Guernica?',
];

function csrfHeaders() {
  const cookies = http.cookieJar().cookiesForURL(BASE);
  if (!cookies['XSRF-TOKEN']) {
    http.get(`${BASE}/api/system/status`, { tags: { name: 'status' } });
  }
  return { 'X-XSRF-TOKEN': http.cookieJar().cookiesForURL(BASE)['XSRF-TOKEN'][0] };
}

function signIn(email) {
  const response = http.post(
    `${BASE}/api/auth/login`,
    { email, password: PASSWORD },
    { headers: csrfHeaders(), tags: { name: 'sign in' } },
  );
  check(response, { 'signed in': (r) => r.status === 204 });
}

function waitUntilFinished(id) {
  const deadline = Date.now() + 10 * 60 * 1000;
  while (Date.now() < deadline) {
    const response = http.get(`${BASE}/api/documents/${id}`, { tags: { name: 'check document' } });
    const status = response.status === 200 ? response.json('status') : null;
    if (status !== 'PROCESSING') {
      return status;
    }
    sleep(0.25);
  }
  return 'STILL_PROCESSING';
}

function uploadFile(filename, content) {
  return http.post(
    `${BASE}/api/documents`,
    { file: http.file(content, filename, 'application/pdf') },
    { headers: csrfHeaders(), tags: { name: 'upload' } },
  );
}

/** Each account has one document, so every question searches its passages. */
export function setup() {
  const accounts = [];
  const count = MODE === 'simulated' ? ACCOUNTS : 1;
  for (let number = 0; number < count; number++) {
    const email = `load-${Date.now()}-${number}@example.com`;
    http.cookieJar().clear(BASE);
    const created = http.post(
      `${BASE}/api/auth/register`,
      JSON.stringify({ email, password: PASSWORD }),
      { headers: { ...csrfHeaders(), 'Content-Type': 'application/json' }, tags: { name: 'register' } },
    );
    if (created.status !== 201) {
      throw new Error(`Could not create an account: ${created.status}`);
    }
    signIn(email);
    const files = MODE === 'scale' ? Array(SCALE_DOCUMENTS).fill(largestDocument()) : [MODE === 'real' ? REAL_DOCUMENT : SIMULATED_DOCUMENT];
    let last = null;
    for (const file of files) {
      const uploaded = uploadFile('document.pdf', file);
      last = uploaded.json('id');
      if (waitUntilFinished(last) !== 'READY') {
        throw new Error('A document did not become ready');
      }
    }
    accounts.push({ email, document: last });
  }
  http.cookieJar().clear(BASE);
  return { accounts };
}

// Each virtual user keeps its session, as a browser does, and signs in only once.
let account = null;

function signedIn(data) {
  if (account === null) {
    account = data.accounts[(__VU - 1) % data.accounts.length];
    signIn(account.email);
  }
  return account;
}

export function browse(data) {
  const { document } = signedIn(data);
  const page = 1 + Math.floor(Math.random() * PAGES_PER_DOCUMENT);

  check(http.get(`${BASE}/api/documents?page=0&size=10`, { tags: { name: 'list documents' } }), {
    'documents listed': (r) => r.status === 200,
  });
  sleep(1);
  check(http.get(`${BASE}/api/conversations?page=0&size=20`, { tags: { name: 'list conversations' } }), {
    'conversations listed': (r) => r.status === 200,
  });
  sleep(1);
  check(http.get(`${BASE}/api/documents/${document}/pages/${page}`, { tags: { name: 'read page' } }), {
    'page read': (r) => r.status === 200,
  });
  sleep(1);
}

export function askQuestion(data) {
  signedIn(data);
  const number = 1 + Math.floor(Math.random() * PAGES_PER_DOCUMENT);
  const question =
    MODE === 'real'
      ? REAL_QUESTIONS[__ITER % REAL_QUESTIONS.length]
      : `What is the notice period of clause ${number}?`;
  const response = http.post(`${BASE}/api/conversations`, JSON.stringify({ question }), {
    headers: { ...csrfHeaders(), 'Content-Type': 'application/json' },
    tags: { name: 'ask' },
    // Longer than the application's own limit, because the first question can wait for a model to load.
    timeout: '180s',
    // Being refused above the limit of questions at once is the application working as designed.
    responseCallback: http.expectedStatuses(201, 429),
  });
  if (response.status === 429) {
    questionsRefused.add(1);
  }
  check(response, { 'question answered or refused': (r) => r.status === 201 || r.status === 429 });
}

export function uploadDocument(data) {
  signedIn(data);
  const started = Date.now();
  const uploaded = uploadFile('small.pdf', SMALL_DOCUMENT);
  if (!check(uploaded, { 'file accepted': (r) => r.status === 202 })) {
    return;
  }
  const id = uploaded.json('id');
  const status = waitUntilFinished(id);
  documentReadyTime.add(Date.now() - started);
  check(status, { 'document became ready': (value) => value === 'READY' });
  http.del(`${BASE}/api/documents/${id}`, null, { headers: csrfHeaders(), tags: { name: 'delete' } });
}

export function handleSummary(data) {
  return { '/load/results/k6-summary.json': JSON.stringify(data, null, 2) };
}
