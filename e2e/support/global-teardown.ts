import { removeStack } from './stack';

/** With E2E_KEEP_STACK=1 the stack stays up, to look at it after a failure. The next run replaces it. */
export default function globalTeardown(): void {
  if (process.env.E2E_KEEP_STACK !== '1') {
    removeStack();
  }
}
