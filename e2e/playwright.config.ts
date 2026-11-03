import { defineConfig } from '@playwright/test';

/** The tests share one stack and stop its services, so they run one at a time. */
export default defineConfig({
  testDir: './tests',
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  timeout: 120_000,
  expect: { timeout: 30_000 },
  reporter: 'list',
  outputDir: 'test-results',
  globalSetup: './support/global-setup.ts',
  globalTeardown: './support/global-teardown.ts',
  use: {
    baseURL: 'http://localhost:4300',
    // The Edge that comes with Windows, so no browser has to be downloaded.
    channel: 'msedge',
    headless: true,
    viewport: { width: 1280, height: 900 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
});
