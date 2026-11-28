import { tokens } from '../theme/theme';

/**
 * Preset avatars: the cabinet's 3D illustrations on the bright tiles of the landing.
 * Codes mirror Avatar.PRESETS in the identity service.
 */
export const avatarPresets = [
  { code: 'briefcase', title: 'Портфель', background: tokens.tilePeach },
  { code: 'chart', title: 'График', background: tokens.tileCyan },
  { code: 'coin', title: 'Монета', background: tokens.tileLime },
  { code: 'rocket', title: 'Ракета', background: tokens.tilePink },
  { code: 'factory', title: 'Производство', background: tokens.tileGreen },
  { code: 'truck', title: 'Доставка', background: tokens.tileSalmon },
  { code: 'cart', title: 'Торговля', background: tokens.tileCyan },
  { code: 'house', title: 'Дом', background: tokens.tilePeach },
  { code: 'gem', title: 'Кристалл', background: tokens.tilePink },
  { code: 'handshake', title: 'Партнёрство', background: tokens.tileLime },
  { code: 'shield', title: 'Защита', background: tokens.tileGreen },
  { code: 'bank', title: 'Банк', background: tokens.tileSalmon },
] as const;

export type AvatarPreset = (typeof avatarPresets)[number];

export function presetOf(avatar: string | null | undefined): AvatarPreset | null {
  if (!avatar?.startsWith('preset:')) return null;
  const code = avatar.slice('preset:'.length);
  return avatarPresets.find((preset) => preset.code === code) ?? null;
}

export function isPhoto(avatar: string | null | undefined): avatar is string {
  return !!avatar && /^data:image\/(png|jpeg|webp);base64,/.test(avatar);
}

export const AVATAR_SIZE = 160;
export const AVATAR_MAX_BYTES = 64 * 1024;

/** Crops the picture to a centred square and scales it down, so the upload stays well under 64 KB. */
export async function prepareAvatarPhoto(file: File): Promise<string> {
  if (!/^image\/(png|jpeg|webp)$/.test(file.type)) throw new Error('Подойдёт фото в PNG, JPEG или WebP');
  if (file.size > 15 * 1024 * 1024) throw new Error('Файл больше 15 МБ — выберите фото поменьше');
  const bitmap = await createImageBitmap(file);
  const side = Math.min(bitmap.width, bitmap.height);
  const canvas = document.createElement('canvas');
  canvas.width = AVATAR_SIZE;
  canvas.height = AVATAR_SIZE;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Браузер не смог обработать фото');
  context.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, AVATAR_SIZE, AVATAR_SIZE);
  bitmap.close?.();
  for (const quality of [0.86, 0.72, 0.58]) {
    const url = canvas.toDataURL('image/jpeg', quality);
    if (Math.ceil(((url.length - url.indexOf(',') - 1) * 3) / 4) <= AVATAR_MAX_BYTES) return url;
  }
  throw new Error('Не удалось уменьшить фото до 64 КБ');
}
