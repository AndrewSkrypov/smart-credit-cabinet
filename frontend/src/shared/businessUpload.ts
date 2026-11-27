import { readSession } from '../auth/session';

export interface BusinessUploadOperation {
  file: File;
  key: string;
  scope: string;
}

// In-memory only: neither the file nor the session token is persisted.
export function businessUploadScope(...flow: string[]): string {
  const session = readSession();
  return JSON.stringify([session?.token, session?.user.id, session?.user.role, ...flow]);
}

export function createBusinessUpload(file: File, scope: string): BusinessUploadOperation {
  return { file, scope, key: crypto.randomUUID() };
}
