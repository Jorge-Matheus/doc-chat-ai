// A stand-in for Ollama, used only by the browser tests. It answers the calls the backend makes with
// predictable results and no GPU, and tests can make it slow or broken through /__control.
// It follows the same rules as FakeEmbeddingModel and FakeChatModel in the backend's tests.
import { createServer } from 'node:http';

const DIMENSIONS = 1024;
const MODELS = (process.env.MODELS ?? 'qwen3.5:9b,bge-m3:latest').split(',');
const REWRITE_MARKER = '<conversation>';
const LATEST_QUESTION = 'Latest question: ';

const DEFAULTS = {
  chatDelayMs: 0,
  embedDelayMs: 0,
  failChat: false,
  failEmbed: false,
  // What the model says instead of repeating the first passage.
  reply: null,
  // What a follow-up becomes instead of staying as it was asked.
  rewriteReply: null,
};

let control = { ...DEFAULTS };
let calls = { chat: 0, embed: 0 };

function hashOf(word) {
  let hash = 0;
  for (const character of word) {
    hash = (Math.imul(31, hash) + character.codePointAt(0)) | 0;
  }
  return ((hash % DIMENSIONS) + DIMENSIONS) % DIMENSIONS;
}

/** Texts sharing words are similar, and unrelated texts are not. */
function vectorOf(text) {
  const vector = new Array(DIMENSIONS).fill(0);
  for (const word of text.toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (word.length >= 3) {
      vector[hashOf(word)] += 1;
    }
  }
  const length = Math.hypot(...vector);
  if (length === 0) {
    vector[0] = 1;
    return vector;
  }
  return vector.map((value) => value / length);
}

function chatReply(messages) {
  const prompt = messages.map((message) => message.content).join('\n');
  if (prompt.includes(REWRITE_MARKER)) {
    const latest = prompt.slice(prompt.lastIndexOf(LATEST_QUESTION) + LATEST_QUESTION.length).trim();
    return control.rewriteReply ?? latest;
  }
  if (control.reply !== null) {
    return control.reply;
  }
  const firstSource = /<source number="1">\n([\s\S]*?)\n<\/source>/.exec(prompt);
  return firstSource ? `${firstSource[1].slice(0, 200)} [1].` : 'NOT_FOUND';
}

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function respond(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
}

const COUNTERS = { total_duration: 1, load_duration: 1, prompt_eval_count: 1 };

const routes = {
  'GET /api/tags': (_body, response) =>
    respond(response, 200, {
      models: MODELS.map((name) => ({
        name,
        model: name,
        modified_at: '2026-01-01T00:00:00Z',
        size: 1,
        digest: 'fake',
        details: {},
      })),
    }),

  'POST /api/embed': async (body, response) => {
    calls.embed++;
    await wait(control.embedDelayMs);
    if (control.failEmbed) {
      return respond(response, 500, { error: 'simulated failure' });
    }
    const input = Array.isArray(body.input) ? body.input : [body.input];
    return respond(response, 200, { model: body.model, embeddings: input.map(vectorOf), ...COUNTERS });
  },

  'POST /api/chat': async (body, response) => {
    calls.chat++;
    await wait(control.chatDelayMs);
    if (control.failChat) {
      return respond(response, 500, { error: 'simulated failure' });
    }
    return respond(response, 200, {
      model: body.model,
      created_at: new Date().toISOString(),
      message: { role: 'assistant', content: chatReply(body.messages) },
      done: true,
      done_reason: 'stop',
      prompt_eval_duration: 1,
      eval_count: 1,
      eval_duration: 1,
      ...COUNTERS,
    });
  },

  'POST /__control': (body, response) => {
    control = { ...DEFAULTS, ...body };
    calls = { chat: 0, embed: 0 };
    respond(response, 200, control);
  },

  'GET /__calls': (_body, response) => respond(response, 200, calls),
};

createServer(async (request, response) => {
  const route = routes[`${request.method} ${request.url}`];
  if (!route) {
    return respond(response, 404, { error: 'not found' });
  }
  const text = await readBody(request);
  return route(text ? JSON.parse(text) : {}, response);
}).listen(11434, () => console.log('Fake Ollama listening on port 11434'));
