const base = process.env.CABINET_URL ?? 'http://localhost:8080';
const paths = ['/api/v1/auth/options', '/api/v1/products', ...['identity', 'credit', 'engagement', 'external-sim'].map((name) => `/v3/api-docs/${name}`)];
const deadline = Date.now() + 180_000;
const pending = new Set(paths);
while (pending.size && Date.now() < deadline) {
  await Promise.all([...pending].map(async (path) => {
    try {
      const response = await fetch(base + path, { signal: AbortSignal.timeout(5000) });
      if (response.ok) {
        await response.json();
        pending.delete(path);
      }
    } catch { /* A starting service is retried until the shared deadline. */ }
  }));
  if (pending.size) await new Promise((resolve) => setTimeout(resolve, 1000));
}
if (pending.size) throw new Error(`Сервисы не готовы: ${[...pending].join(', ')}`);
console.log('Шлюз, API и спецификации четырёх сервисов готовы.');
