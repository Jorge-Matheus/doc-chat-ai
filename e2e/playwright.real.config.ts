import base from './playwright.config';
import { defineConfig } from '@playwright/test';

// Read by the global setup, which starts the stack with the real models instead of the fake one.
process.env.E2E_REAL_MODELS = '1';

/** The real models take seconds per question, and minutes when they have to be loaded first. */
export default defineConfig({
  ...base,
  testDir: './real',
  timeout: 900_000,
  expect: { timeout: 180_000 },
});
