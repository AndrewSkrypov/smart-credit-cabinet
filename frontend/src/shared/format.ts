const money = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
const moneyExact = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dateTime = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
const date = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });

/** 1 500 000 ₽ */
export const rub = (value: number) => `${money.format(value)} ₽`;

/** 88 848,79 ₽ — payments, with kopecks. */
export const rubExact = (value: number) => `${moneyExact.format(value)} ₽`;

export const percent = (value: number) => `${String(value).replace('.', ',')}%`;

export const formatDateTime = (iso: string) => dateTime.format(new Date(iso));

export const formatDate = (iso: string) => date.format(new Date(iso));

/** «12 месяцев», «1 месяц», «3 месяца». */
export function months(count: number): string {
  const mod10 = count % 10;
  const mod100 = count % 100;
  if (mod10 === 1 && mod100 !== 11) return `${count} месяц`;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return `${count} месяца`;
  return `${count} месяцев`;
}

export const fileSize = (bytes: number) => (bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} КБ` : `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} МБ`);
