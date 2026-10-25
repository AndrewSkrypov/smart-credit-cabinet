import DownloadOutlinedIcon from '@mui/icons-material/DownloadOutlined';
import { Alert, Button, Chip, Paper, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { artifactsApi } from '../api/client';
import { useApi } from '../hooks/useApi';
import { fileSize } from '../shared/format';
import { GeneratedArtifact } from '../types';
import { ErrorState } from './ErrorState';
import { LoadingBlock } from './LoadingBlock';

export function GeneratedFilesBlock({ applicationId, final }: { applicationId: string; final: boolean }) {
  const artifacts = useApi(() => artifactsApi.list(applicationId), [applicationId, final]);
  const { reload } = artifacts;
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const waiting = artifacts.data?.some((item) => item.status !== 'READY') ?? false;
  useEffect(() => {
    if (!waiting) return;
    const timer = window.setInterval(() => void reload(), 5000);
    return () => window.clearInterval(timer);
  }, [waiting, reload]);

  const act = async (item: GeneratedArtifact, download: boolean) => {
    if (busy) return;
    setBusy(item.id); setError(null);
    try {
      if (download) await artifactsApi.download(applicationId, item.id, item.fileName);
      else await artifactsApi.retry(applicationId, item.id);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Не удалось получить файл'); }
    finally { await artifacts.reload(); setBusy(null); }
  };

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6" component="h2" gutterBottom>{final ? 'Файлы по кредиту' : 'Предварительные файлы'}</Typography>
      <Typography color="text.secondary" sx={{ mb: 2 }}>{final ? 'Сохранённые условия и график платежей.' : 'Предварительный график по условиям предложения. После выдачи будет доступен окончательный.'}</Typography>
      {artifacts.loading && !artifacts.data && <LoadingBlock />}
      {artifacts.error && <ErrorState message={artifacts.error} onRetry={artifacts.reload} />}
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      <Stack spacing={2}>
        {artifacts.data?.map((item) => {
          const format = item.kind === 'SCHEDULE_XLSX' ? 'XLSX' : 'PDF';
          return <Stack key={item.id} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ sm: 'center' }}>
            <Stack spacing={0.5} sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>
              <Typography fontWeight={600}>{item.title}</Typography>
              <Typography variant="body2" color="text.secondary">{format}{item.sizeBytes != null && item.status === 'READY' ? ` · ${fileSize(item.sizeBytes)}` : ''}</Typography>
              {item.errorMessage && <Typography variant="body2" color="error.main">{item.errorMessage}</Typography>}
            </Stack>
            {item.status === 'READY' ? <Button variant="text" startIcon={<DownloadOutlinedIcon />} sx={{ flexShrink: 0, alignSelf: { xs: 'flex-start', sm: 'center' }, minHeight: 44 }} disabled={busy !== null} aria-label={`Скачать ${item.title}, ${format}`} onClick={() => void act(item, true)}>{busy === item.id ? 'Скачиваем…' : `Скачать ${format}`}</Button>
              : item.status === 'ERROR' ? <Button variant="outlined" disabled={busy !== null} onClick={() => void act(item, false)}>Повторить создание</Button>
                : <Chip label="Готовим файл…" size="small" />}
          </Stack>;
        })}
      </Stack>
    </Paper>
  );
}
