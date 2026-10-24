import { Alert } from '@mui/material';

const notice = {
  full: 'Не отправляйте полный номер карты, срок её действия вместе с номером, CVV/CVC, PIN-код или фотографию карты. В документах заранее скройте эти реквизиты. Кабинету они не нужны.',
  compact: 'Не вводите реквизиты платёжной карты и PIN-код. Для ответа помощнику они не нужны.'
};

/** Input guidance only: does not claim to detect, redact or delete card data. */
export function SensitiveDataNotice({ compact = false }: { compact?: boolean }) {
  return <Alert severity="info" icon={false} sx={{ mb: 2 }}>{compact ? notice.compact : notice.full}</Alert>;
}
