import { Alert, Box, Button, Checkbox, Chip, FormControlLabel, Grid, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { productEditorApi, productsApi } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { formatDateTime, rub } from '../../shared/format';
import { FinancingPurpose, Product } from '../../types';

export function ProductsPage() {
  const products = useApi(productEditorApi.list);
  const purposes = useApi(productsApi.purposes);
  const [selected, setSelected] = useState('');
  const code = selected || products.data?.[0]?.code || '';
  return (
    <>
      <PageHeader title="Продукты" subtitle="Условия каталога и история изменений. Поданные заявки сохраняют прежние условия." />
      {products.loading && !products.data ? <LoadingBlock /> : products.error ? <ErrorState message={products.error} onRetry={products.reload} /> : (
        <Stack spacing={3}>
          <Paper sx={{ p: { xs: 3, md: 4 } }}>
            <TextField select label="Продукт" fullWidth value={code} onChange={(event) => setSelected(event.target.value)}>
              {products.data?.map((product) => <MenuItem key={product.code} value={product.code}>{product.name}{product.active === false ? ' · выключен' : ''}</MenuItem>)}
            </TextField>
          </Paper>
          {code && <Box><ProductCard key={code} code={code} purposes={purposes.data ?? []} onSaved={products.reload} /></Box>}
        </Stack>
      )}
    </>
  );
}

function ProductCard({ code, purposes, onSaved }: { code: string; purposes: { code: FinancingPurpose; title: string }[]; onSaved: () => Promise<void> }) {
  const { session } = useAuth();
  const canEdit = session?.user.role === 'RISK_ADMIN';
  const history = useApi(() => productEditorApi.history(code), [code]);
  const current = history.data?.[0]?.product;
  const [draft, setDraft] = useState<Product | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const value = draft ?? current;
  const change = (patch: Partial<Product>) => value && setDraft({ ...value, ...patch });
  const save = async () => {
    if (!value || busy) return;
    setBusy(true); setError(null); setNotice(null);
    try {
      const saved = await productEditorApi.save(value, reason);
      setNotice(`Сохранена версия ${saved.product.version}. Новые заявки используют эти условия.`);
      setDraft(null); setReason('');
      await history.reload(); await onSaved();
    } catch (next) { setError(next instanceof Error ? next.message : 'Не удалось сохранить продукт'); }
    finally { setBusy(false); }
  };
  if (history.loading && !value) return <LoadingBlock />;
  if (history.error || !value) return <ErrorState message={history.error ?? 'Продукт не найден'} onRetry={history.reload} />;
  const numberField = (key: 'minAmount' | 'maxAmount' | 'minTermMonths' | 'maxTermMonths' | 'baseRate' | 'autoApprovalLimit', label: string) => (
    <Grid item xs={12} sm={6} key={key}>
      <TextField label={label} fullWidth type="number" value={value[key]} disabled={!canEdit || busy}
        inputProps={{ min: 0, step: key === 'baseRate' ? '0.01' : '1' }} onChange={(event) => change({ [key]: Number(event.target.value) })} />
    </Grid>
  );
  return (
    <Grid container spacing={3}>
      <Grid item xs={12} lg={8}>
        <Paper sx={{ p: { xs: 3, md: 4 } }}>
          <Stack spacing={2.5}>
            <Typography variant="h6">Условия продукта</Typography>
            <Stack direction="row" spacing={1}><Chip label={`Версия ${value.version}`} /><Chip label={value.active ? 'В каталоге' : 'Выключен'} /></Stack>
            {!canEdit && <Alert severity="info">Изменять продукты может риск-администратор. Вам доступен просмотр условий и истории.</Alert>}
            {notice && <Alert severity="success">{notice}</Alert>}
            {error && <Alert severity="error">{error}<Button onClick={async () => { setDraft(null); setReason(''); setError(null); await history.reload(); }}>Обновить карточку</Button></Alert>}
            <TextField label="Название продукта" value={value.name} disabled={!canEdit || busy} inputProps={{ maxLength: 255 }} onChange={(event) => change({ name: event.target.value })} />
            <TextField label="Описание продукта" multiline minRows={3} value={value.description} disabled={!canEdit || busy} inputProps={{ maxLength: 5000 }} onChange={(event) => change({ description: event.target.value })} />
            <Box><Grid container spacing={2}>
              {numberField('baseRate', 'Базовая ставка, %')}
              {numberField('autoApprovalLimit', 'Лимит автоодобрения, ₽')}
              {numberField('minAmount', 'Минимальная сумма, ₽')}
              {numberField('maxAmount', 'Максимальная сумма, ₽')}
              {numberField('minTermMonths', 'Минимальный срок, месяцев')}
              {numberField('maxTermMonths', 'Максимальный срок, месяцев')}
            </Grid></Box>
            <Typography fontWeight={600}>Цели финансирования</Typography>
            <Stack>
              {purposes.map((purpose) => <FormControlLabel key={purpose.code} label={purpose.title} control={<Checkbox disabled={!canEdit || busy} checked={value.purposes.includes(purpose.code)} onChange={(_, checked) => change({ purposes: checked ? [...value.purposes, purpose.code] : value.purposes.filter((item) => item !== purpose.code) })} />} />)}
            </Stack>
            <FormControlLabel label="Возобновляемый лимит" control={<Checkbox disabled={!canEdit || busy} checked={value.revolving} onChange={(_, checked) => change({ revolving: checked })} />} />
            <FormControlLabel label="Показывать в каталоге и принимать новые заявки" control={<Checkbox disabled={!canEdit || busy} checked={value.active !== false} onChange={(_, checked) => change({ active: checked })} />} />
            {canEdit && <>
              <TextField label="Причина изменения продукта" required multiline minRows={2} inputProps={{ maxLength: 1000 }} value={reason} onChange={(event) => setReason(event.target.value)} disabled={busy} />
              <Button variant="contained" disabled={busy || !reason.trim()} onClick={() => void save()}>Сохранить новую версию продукта</Button>
            </>}
          </Stack>
        </Paper>
      </Grid>
      <Grid item xs={12} lg={4}>
        <Paper sx={{ p: { xs: 3, md: 4 } }}>
          <Typography variant="h6" sx={{ mb: 2 }}>История версий продукта</Typography>
          <Stack spacing={3}>
            {history.data?.map((revision) => <Stack spacing={0.75} key={revision.product.version}>
              <Typography fontWeight={600}>Версия {revision.product.version} · {revision.product.baseRate}%</Typography>
              <Typography variant="body2">{rub(revision.product.minAmount)} — {rub(revision.product.maxAmount)} · {revision.product.active ? 'В каталоге' : 'Выключен'}</Typography>
              <Typography variant="body2" color="text.secondary">{formatDateTime(revision.createdAt)}</Typography>
              <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>Автор: {revision.authorId ?? 'Начальный каталог'}</Typography>
              <Typography sx={{ overflowWrap: 'anywhere' }}>{revision.reason}</Typography>
              <Typography component="details" variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                <summary>Все условия версии {revision.product.version}</summary>
                {revision.product.name}<br />{revision.product.description}<br />
                Срок: {revision.product.minTermMonths} — {revision.product.maxTermMonths} месяцев.<br />
                Лимит автоодобрения: {rub(revision.product.autoApprovalLimit)}.<br />
                {revision.product.revolving ? 'Возобновляемый лимит.' : 'Разовая выдача.'}<br />
                Цели: {revision.product.purposes.map((code) => purposes.find((purpose) => purpose.code === code)?.title ?? code).join('; ')}.
              </Typography>
            </Stack>)}
          </Stack>
        </Paper>
      </Grid>
    </Grid>
  );
}
