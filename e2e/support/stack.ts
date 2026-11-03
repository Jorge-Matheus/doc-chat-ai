import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * With a lower-case drive letter, which some terminals on Windows use, Docker takes the folders of
 * the images to be outside the current one and refuses to build them.
 */
const E2E_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..').replace(/^[a-z]:/, (drive) => drive.toUpperCase());

/** Always names the test project, so these commands can never reach the stack with real data. */
const PROJECT = ['compose', '-p', 'docchat-e2e', '-f', join(E2E_ROOT, 'compose.yaml')];

function compose(args: string[], options: { show?: boolean; env?: NodeJS.ProcessEnv } = {}): string {
  return execFileSync('docker', [...PROJECT, ...args], {
    cwd: E2E_ROOT,
    encoding: 'utf8',
    env: { ...process.env, ...options.env },
    stdio: options.show ? 'inherit' : ['ignore', 'pipe', 'pipe'],
  });
}

const REAL_MODELS_ENV = {
  E2E_OLLAMA_URL: 'http://host.docker.internal:11434',
  E2E_QUESTION_TIME_LIMIT: '170s',
  E2E_PROCESSING_TIME_LIMIT: '30m',
};

export function startStack(realModels: boolean): void {
  // Anything left by an interrupted run must not affect this one.
  compose(['down', '-v', '--remove-orphans'], { show: true });
  const services = realModels ? ['db', 'backend', 'frontend'] : ['db', 'fake-ollama', 'backend', 'frontend'];
  compose(['up', '-d', '--build', '--wait', ...services], { show: true, env: realModels ? REAL_MODELS_ENV : {} });
}

export function removeStack(): void {
  compose(['down', '-v', '--remove-orphans'], { show: true });
}

export type Service = 'db' | 'fake-ollama' | 'backend' | 'frontend';

export function stopService(service: Service): void {
  compose(['stop', service]);
}

/** Returns once the service is healthy again. */
export function startService(service: Service): void {
  compose(['up', '-d', '--wait', '--no-deps', service]);
}

export function restartService(service: Service): void {
  compose(['restart', service]);
}

const READINESS_URL = 'http://localhost:18080/actuator/health/readiness';

/** The backend reconnects to the database some seconds after it returns. */
async function backendIsReady(): Promise<void> {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    const status = await fetch(READINESS_URL).then(
      (response) => response.status,
      () => 0,
    );
    if (status === 200) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('The backend did not become ready again');
}

/** Stops a service until the end of the work, and always brings it back for the next test. */
export async function whileStopped(service: Service, work: () => Promise<void>): Promise<void> {
  stopService(service);
  try {
    await work();
  } finally {
    startService(service);
    await backendIsReady();
  }
}

/** Runs a query as the database's own user, for what the application does not expose. */
export function sql(query: string): string {
  return compose(['exec', '-T', 'db', 'psql', '-U', 'docchat', '-d', 'docchat', '-Atc', query]).trim();
}
