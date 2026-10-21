/** «3 ч 20 мин», «45 мин», «2 дн 4 ч» — how long something has been waiting. */
export function waitingFor(sinceIso: string, now: number = Date.now()): string {
  const minutes = Math.max(0, Math.floor((now - new Date(sinceIso).getTime()) / 60000));
  if (minutes < 60) return `${minutes} мин`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} ч ${minutes % 60} мин`;
  return `${Math.floor(hours / 24)} дн ${hours % 24} ч`;
}

export const hoursSince = (sinceIso: string, now: number = Date.now()) => (now - new Date(sinceIso).getTime()) / 3600000;

/** Minutes as read in the funnel: «12 с», «4 мин», «3,5 ч». */
export function formatMinutes(minutes: number | null): string {
  if (minutes == null) return '—';
  if (minutes < 1) return `${Math.max(1, Math.round(minutes * 60))} с`;
  if (minutes < 120) return `${Math.round(minutes)} мин`;
  return `${(minutes / 60).toFixed(1).replace('.', ',')} ч`;
}
