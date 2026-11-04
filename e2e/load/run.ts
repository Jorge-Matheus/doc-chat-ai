// Starts the isolated stack, runs the load test against it, and reports what happened. See
// docs/performance.md. Run with npm run load, npm run load:real for the real models, or
// npm run load:scale to fill one account with documents.
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { E2E_ROOT, removeStack, runLoadTest, startLoadStack } from '../support/stack.ts';

const REAL_MODELS = process.argv.includes('--real');
const MODE = REAL_MODELS ? 'real' : process.argv.includes('--scale') ? 'scale' : 'simulated';
const RESULTS = join(E2E_ROOT, 'load', 'results');
const PROMETHEUS = 'http://localhost:19090';

interface ResourceSample {
  service: string;
  cpu: number;
  memoryMb: number;
}

const samples: ResourceSample[] = [];

/** One reading of every container of the stack. It takes seconds, so readings do not overlap. */
function sampleResources(): Promise<void> {
  return new Promise((resolve) => {
    const stats = spawn('docker', ['stats', '--no-stream', '--format', '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}']);
    let output = '';
    stats.stdout.on('data', (chunk: Buffer) => (output += chunk.toString()));
    stats.on('close', () => {
      for (const line of output.split(/\r?\n/)) {
        const [name, cpu, memory] = line.split('|');
        if (name?.startsWith('docchat-e2e-')) {
          const amount = Number.parseFloat(memory);
          samples.push({
            service: name.replace('docchat-e2e-', '').replace(/-\d+$/, ''),
            cpu: Number.parseFloat(cpu),
            memoryMb: /GiB/.test(memory.split('/')[0]) ? amount * 1024 : amount,
          });
        }
      }
      resolve();
    });
  });
}

function summarizeResources(): Record<string, { avgCpuPercent: number; maxCpuPercent: number; maxMemoryMb: number }> {
  const result: Record<string, { avgCpuPercent: number; maxCpuPercent: number; maxMemoryMb: number }> = {};
  for (const service of new Set(samples.map((sample) => sample.service))) {
    const own = samples.filter((sample) => sample.service === service);
    result[service] = {
      avgCpuPercent: Math.round(own.reduce((sum, sample) => sum + sample.cpu, 0) / own.length),
      maxCpuPercent: Math.round(Math.max(...own.map((sample) => sample.cpu))),
      maxMemoryMb: Math.round(Math.max(...own.map((sample) => sample.memoryMb))),
    };
  }
  return result;
}

async function queryPrometheus(query: string, at: number): Promise<{ metric: Record<string, string>; value: [number, string] }[]> {
  const url = `${PROMETHEUS}/api/v1/query?query=${encodeURIComponent(query)}&time=${at}`;
  const body = (await (await fetch(url)).json()) as { data: { result: { metric: Record<string, string>; value: [number, string] }[] } };
  return body.data.result;
}

/** The percentiles of each stage over the whole run, which k6 cannot see from outside. */
async function stagePercentiles(minutes: number, at: number): Promise<Record<string, Record<string, number>>> {
  const result: Record<string, Record<string, number>> = {};
  const metrics = {
    question: 'docchat_question_stage',
    document: 'docchat_document_stage',
  };
  for (const [kind, metric] of Object.entries(metrics)) {
    for (const quantile of [0.5, 0.95, 0.99]) {
      const query = `histogram_quantile(${quantile}, sum by (le, stage) (increase(${metric}_seconds_bucket[${minutes}m])))`;
      for (const { metric: labels, value } of await queryPrometheus(query, at)) {
        const key = `${kind}: ${labels.stage}`;
        result[key] = { ...result[key], [`p${quantile * 100}`]: Number(Number(value[1]).toFixed(4)) };
      }
    }
  }
  return result;
}

function printTable(summary: { metrics: Record<string, { values: Record<string, number> }> }): void {
  console.log('\nRequest                   count      p50      p95      p99      max     (ms)');
  for (const [name, metric] of Object.entries(summary.metrics)) {
    const match = /^http_req_duration\{name:(.+)}$/.exec(name);
    const label = match ? match[1] : name === 'document_ready_time' ? 'document until ready' : null;
    if (label) {
      const v = metric.values;
      const cell = (value: number): string => value.toFixed(0).padStart(9);
      console.log(`${label.padEnd(24)}${String(v.count ?? '').padStart(7)}${cell(v.med)}${cell(v['p(95)'])}${cell(v['p(99)'])}${cell(v.max)}`);
    }
  }
}

mkdirSync(RESULTS, { recursive: true });
startLoadStack(REAL_MODELS);

const started = Date.now();
let sampling = true;
const sampler = (async () => {
  while (sampling) {
    await sampleResources();
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }
})();

const exitCode = await runLoadTest({ E2E_LOAD_MODE: MODE });

sampling = false;
await sampler;
// Lets Prometheus read the final numbers.
await new Promise((resolve) => setTimeout(resolve, 20_000));

const minutes = Math.ceil((Date.now() - started) / 60_000);
const summary = JSON.parse(readFileSync(join(RESULTS, 'k6-summary.json'), 'utf8')) as { metrics: Record<string, { values: Record<string, number> }> };
const report = {
  mode: MODE,
  finishedAt: new Date().toISOString(),
  minutes,
  thresholdsMet: exitCode === 0,
  stages: await stagePercentiles(minutes, Date.now() / 1000),
  resources: summarizeResources(),
  iterationsPerSecond: summary.metrics.iterations?.values.rate,
  requestsPerSecond: summary.metrics.http_reqs?.values.rate,
  failedRequests: summary.metrics.http_req_failed?.values.rate,
  questionsRefused: summary.metrics.questions_refused?.values.count ?? 0,
};
writeFileSync(join(RESULTS, `report-${MODE}.json`), JSON.stringify(report, null, 2));

printTable(summary);
console.log('\nTime per stage (seconds), from the application\'s own metrics');
console.table(report.stages);
console.log('Resources during the run');
console.table(report.resources);
console.log(`Requests per second: ${report.requestsPerSecond?.toFixed(1)}. Failed requests: ${((report.failedRequests ?? 0) * 100).toFixed(2)}%. Questions refused: ${report.questionsRefused}.`);
console.log(exitCode === 0 ? 'The thresholds were met.' : `The thresholds were NOT met (k6 exit code ${exitCode}).`);

if (process.env.E2E_KEEP_STACK === '1') {
  console.log('The stack stays up: Grafana at http://localhost:13000, Prometheus at http://localhost:19090');
} else {
  removeStack();
}
process.exit(exitCode);
