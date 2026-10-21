import { describe, expect, it } from 'vitest';
import { readableComment } from './AssessmentBlocks';

describe('readableComment', () => {
  it('drops the raw answer of a source and keeps the words before it', () => {
    expect(readableComment('БКИ: 503 : "{"timestamp":"2026-10-21","status":503}"')).toBe('БКИ: 503');
    expect(readableComment('Клиент передумал')).toBe('Клиент передумал');
    expect(readableComment('{"status":503}')).toBeNull();
    expect(readableComment(null)).toBeNull();
  });
});
