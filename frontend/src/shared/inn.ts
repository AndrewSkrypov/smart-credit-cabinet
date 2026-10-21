const W10 = [2, 4, 10, 3, 5, 9, 4, 6, 8];
const W11 = [7, 2, 4, 10, 3, 5, 9, 4, 6, 8];
const W12 = [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8];

const checkDigit = (digits: string, weights: number[]) => (weights.reduce((sum, weight, index) => sum + weight * Number(digits[index]), 0) % 11) % 10;

/** Check digits of the Federal Tax Service: 10 digits for an organisation, 12 for an entrepreneur. */
export function isValidInn(value: string): boolean {
  if (!/^\d{10}$|^\d{12}$/.test(value)) return false;
  if (value.length === 10) return checkDigit(value, W10) === Number(value[9]);
  return checkDigit(value, W11) === Number(value[10]) && checkDigit(value, W12) === Number(value[11]);
}

/**
 * A synthetic ИНН for the demo: "00" is no Russian region, so it never belongs to a real
 * business, and the registry simulator describes a typical company for it.
 */
export function generateTestInn(legalEntity: boolean, random: () => number = Math.random): string {
  const body = Array.from({ length: legalEntity ? 7 : 8 }, () => Math.floor(random() * 10)).join('');
  const base = `00${body}`;
  if (legalEntity) return base + checkDigit(base, W10);
  const withFirst = base + checkDigit(base, W11);
  return withFirst + checkDigit(withFirst, W12);
}
