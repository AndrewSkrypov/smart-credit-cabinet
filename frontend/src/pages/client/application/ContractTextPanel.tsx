import { Alert, Box, Typography } from '@mui/material';
import { ContractTextView } from '../../../types';
import { formatDateTime } from '../../../shared/format';

export function ContractTextPanel({ contract }: { contract: ContractTextView }) {
  return (
    <Box sx={{ my: 2 }}>
      <Typography variant="subtitle1" component="h3" fontWeight={600}>{contract.legacySummary ? 'Сводка сохранённых условий' : 'Текст договора'}</Typography>
      {contract.legacySummary && <Alert severity="info" sx={{ my: 1 }}>Исходный текст прежней сессии не сохранён. Эта сводка не является подписанным оригиналом.</Alert>}
      <Box role="region" aria-label="Текст условий договора" tabIndex={0} sx={{ mt: 1, p: 2, maxHeight: 360, overflowY: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', bgcolor: 'action.hover', borderRadius: 2 }}>
        {contract.text}
      </Box>
      {contract.signedAt && <Typography variant="body2" sx={{ mt: 1 }}>Синтетическое подписание: {formatDateTime(contract.signedAt)}.</Typography>}
      {contract.snapshot && <Box component="details" sx={{ mt: 1 }}>
        <Box component="summary" sx={{ cursor: 'pointer', minHeight: 44, py: 1 }}>Сведения о сохранённом тексте</Box>
        <Typography variant="caption" component="p" sx={{ overflowWrap: 'anywhere' }}>Версия шаблона: {contract.snapshot.templateVersion} · Сохранён: {formatDateTime(contract.snapshot.createdAt)}<br />ID: {contract.snapshot.id}<br />SHA-256: {contract.snapshot.sha256}</Typography>
      </Box>}
    </Box>
  );
}
