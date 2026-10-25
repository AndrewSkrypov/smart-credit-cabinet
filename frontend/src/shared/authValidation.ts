import { useState } from 'react';

export const requiredError = 'Поле обязательно для заполнения';
export const passwordHint = '8–72 символа, буквы и цифры; не более 72 байт UTF-8';
export function emailError(value: string) {
  const email = value.trim();
  if (!email) return requiredError;
  if (!/^[^\s@]+@[^\s@]+$/.test(email)) return 'Укажите корректный email';
  const [local, domain] = email.split('@');
  const atom = /^[a-z0-9!#$%&'*+/=?^_`{|}~\u0080-\uffff-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~\u0080-\uffff-]+)*$/iu;
  const quoted = /^"(?:[^"\\]|\\.)+"$/u;
  const label = /^[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?$/u;
  const valid = local.length <= 64 && (atom.test(local) || quoted.test(local)) &&
    domain.length <= 255 && domain.split('.').every(part => part.length <= 63 && label.test(part));
  return valid ? '' : 'Укажите корректный email';
}
export function nameError(value: string) {
  if (!value.trim()) return requiredError;
  return value.trim().length <= 255 ? '' : 'Имя должно быть не длиннее 255 символов';
}
export function passwordError(value: string) {
  if (!value) return requiredError;
  if (value.length < 8 || value.length > 72) return 'Пароль должен быть от 8 до 72 символов';
  if (new TextEncoder().encode(value).length > 72) return 'Пароль слишком длинный: максимум 72 байта UTF-8';
  // Kotlin PasswordPolicy inspects UTF-16 Char values, not supplementary code points.
  const chars = value.split('');
  return chars.some(ch => /\p{L}/u.test(ch)) && chars.some(ch => /\p{Nd}/u.test(ch)) ? '' : 'Пароль должен содержать буквы и цифры';
}

/** An untouched field is neutral; after blur its error follows every correction. */
export function useFieldFeedback(errors: Record<string, string>) {
  const [visited, setVisited] = useState<Record<string, boolean>>({});
  return {
    visitAll: () => setVisited(Object.fromEntries(Object.keys(errors).map(key => [key, true]))),
    field: (key: string, hint?: string) => ({
      id: `auth-${key}`, name: key,
      onBlur: () => setVisited(previous => ({ ...previous, [key]: true })),
      error: !!visited[key] && !!errors[key],
      helperText: visited[key] && errors[key] ? errors[key] : hint
    })
  };
}
