import { startStack } from './stack';

const OLLAMA_URL = 'http://localhost:11434/api/tags';

async function requireRealModels(): Promise<void> {
  try {
    const response = await fetch(OLLAMA_URL);
    if (!response.ok) {
      throw new Error(`Status ${response.status}`);
    }
  } catch (error) {
    throw new Error(
      `The real models were not found at ${OLLAMA_URL} (${String(error)}). ` +
        'Start them from the repository root with: docker compose up -d ollama ollama-models',
    );
  }
}

export default async function globalSetup(): Promise<void> {
  const realModels = process.env.E2E_REAL_MODELS === '1';
  if (realModels) {
    await requireRealModels();
  }
  startStack(realModels);
}
