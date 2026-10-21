import { describe, expect, it } from 'vitest';
import { JournalEntry } from '../types';
import { historyForClient, stageIndex, statusTone } from './applicationText';

const entry = (event: string, id: string): JournalEntry => ({
  id,
  event,
  fromStatus: null,
  toStatus: 'ENRICHMENT',
  actorType: 'SYSTEM',
  actorId: null,
  reasonCode: null,
  comment: null,
  payload: {},
  occurredAt: '2026-10-21T10:00:00Z'
});

describe('application texts', () => {
  it('folds internal statuses into the stages the client sees', () => {
    expect(stageIndex('INFO_REQUESTED')).toBe(stageIndex('SUBMITTED'));
    expect(stageIndex('MANUAL_REVIEW')).toBe(stageIndex('SCORING'));
    expect(stageIndex('DISBURSED')).toBe(7);
  });

  it('marks the statuses that wait for the client', () => {
    expect(statusTone('INFO_REQUESTED')).toBe('warning');
    expect(statusTone('SIGNING')).toBe('warning');
    expect(statusTone('ENRICHMENT')).toBe('info');
  });

  it('shows repeated retries once, newest first', () => {
    const history = historyForClient([entry('SUBMIT', '1'), entry('SOURCE_FAILED', '2'), entry('SOURCE_FAILED', '3'), entry('ENRICHMENT_DONE', '4')]);
    expect(history.map((item) => [item.entry.event, item.repeats])).toEqual([
      ['ENRICHMENT_DONE', 1],
      ['SOURCE_FAILED', 2],
      ['SUBMIT', 1]
    ]);
  });
});
