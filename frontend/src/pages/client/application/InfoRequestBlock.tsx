import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import RadioButtonUncheckedRoundedIcon from '@mui/icons-material/RadioButtonUncheckedRounded';
import { useState } from 'react';
import { applicationsApi } from '../../../api/client';
import { formatDateTime } from '../../../shared/format';
import { Application } from '../../../types';
import { UploadButton } from './UploadButton';
import { SensitiveDataNotice } from '../../../components/SensitiveDataNotice';

/** What the bank asked for, one line per item, with the upload right there and one button to send. */
export function InfoRequestBlock({ application, onChanged, onSent }: { application: Application; onChanged: () => void; onSent: (next: Application) => void }) {
  const request = application.process.infoRequest!;
  const [answer, setAnswer] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const requestedAt = new Date(request.requestedAt).getTime();
  const uploadedSince = (type: string) => application.documents.some((document) => document.type === type && new Date(document.uploadedAt).getTime() >= requestedAt);
  const hasQuestions = request.items.some((item) => item.question);
  const ready = request.items.every((item) => !item.documentType || uploadedSince(item.documentType)) && (!hasQuestions || answer.trim().length > 0);

  const send = async () => {
    setSending(true);
    setError(null);
    try {
      onSent(await applicationsApi.provideInfo(application.id, answer.trim() || undefined));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось отправить');
      setSending(false);
    }
  };

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
      <Typography variant="h6" component="h2">Банк просит дополнить заявку</Typography>
      <Typography color="text.secondary" sx={{ mb: 2 }}>
        Ответить нужно до {formatDateTime(request.deadline)}.{request.comment ? ` ${request.comment}` : ''}
      </Typography>
      <SensitiveDataNotice />
      <Stack spacing={1.5}>
        {request.items.map((item) => {
          const done = item.documentType ? uploadedSince(item.documentType) : false;
          return (
            <Stack key={item.title} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ sm: 'center' }}>
              <Stack direction="row" spacing={1.5} alignItems="flex-start">
                {done ? <CheckCircleRoundedIcon color="success" /> : <RadioButtonUncheckedRoundedIcon color="disabled" />}
                <Box>
                  <Typography fontWeight={600}>{item.title}</Typography>
                  {item.reason && <Typography variant="body2" color="text.secondary">{item.reason}</Typography>}
                </Box>
              </Stack>
              {item.documentType && !done && (
                <UploadButton applicationId={application.id} documentType={item.documentType} onUploaded={onChanged} onError={setError} />
              )}
            </Stack>
          );
        })}
      </Stack>
      {hasQuestions && (
        <TextField label="Ваш ответ" helperText="Это общий ответ на вопросы банка. Перед отправкой проверьте, что ответили на каждый из них." fullWidth multiline minRows={3} sx={{ mt: 2 }} value={answer} onChange={(event) => setAnswer(event.target.value)} inputProps={{ maxLength: 2000 }} />
      )}
      {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      <Button variant="contained" size="large" sx={{ mt: 2 }} disabled={!ready || sending} onClick={send}>
        {sending ? 'Отправляем…' : 'Отправить в банк'}
      </Button>
    </Paper>
  );
}
