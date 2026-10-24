import { Alert, Box, Button, Checkbox, Chip, FormControlLabel, Grid, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { productEditorApi, productsApi } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { SaveVersionDialog, VersionHistory, VersionPreviewDialog } from '../../components/VersionHistory';
import { useApi } from '../../hooks/useApi';
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges';
import { rub } from '../../shared/format';
import { sameVersionParams } from '../../shared/versionParams';
import { FinancingPurpose, Product } from '../../types';

export function ProductsPage() {
  const products = useApi(productEditorApi.list);
  const purposes = useApi(productsApi.purposes);
  const [selected, setSelected] = useState('');
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const guard = useUnsavedChanges(dirty, busy);
  const code = selected || products.data?.[0]?.code || '';
  return (
    <>
      <PageHeader title="Продукты" subtitle="Условия каталога и история изменений. Поданные заявки сохраняют прежние условия." />
      {products.loading && !products.data ? <LoadingBlock /> : products.error ? <ErrorState message={products.error} onRetry={products.reload} /> : (
        <Stack spacing={3}>
          <Paper sx={{ p: { xs: 3, md: 4 } }}>
            <TextField select label="Продукт" fullWidth value={code} onChange={(event) => {
              const next = event.target.value;
              guard.request(() => { setDirty(false); setSelected(next); });
            }}>
              {products.data?.map((product) => <MenuItem key={product.code} value={product.code}>{product.name}{product.active === false ? ' · выключен' : ''}</MenuItem>)}
            </TextField>
          </Paper>
          {code && <Box><ProductCard key={code} code={code} purposes={purposes.data ?? []} onSaved={products.reload} onDirty={setDirty} onBusy={setBusy} requestDiscard={guard.request} /></Box>}
        </Stack>
      )}
      {guard.dialog}
    </>
  );
}

function ProductCard({ code, purposes, onSaved, onDirty, onBusy, requestDiscard }: { code: string; purposes: { code: FinancingPurpose; title: string }[]; onSaved: () => Promise<void>; onDirty: (dirty: boolean) => void; onBusy: (busy: boolean) => void; requestDiscard: (action: () => void) => void }) {
  const { session } = useAuth();
  const canEdit = session?.user.role === 'RISK_ADMIN';
  const history = useApi(() => productEditorApi.history(code), [code]);
  const current = history.data?.[0]?.product;
  const [draft, setDraft] = useState<Product | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { onBusy(busy); return () => onBusy(false); }, [busy, onBusy]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [viewedVersion, setViewedVersion] = useState<number | null>(null);
  const value = draft ?? current;
  const changed = !!draft && !!current && !sameVersionParams(draft, current);
  useEffect(() => onDirty(changed || !!reason.trim()), [changed, reason, onDirty]);
  const viewed = history.data?.find((item) => item.product.version === viewedVersion);
  const change = (patch: Partial<Product>) => value && setDraft({ ...value, ...patch });
  const save = async () => {
    if (!value || busy || !changed || !reason.trim()) return;
    setBusy(true); setError(null); setNotice(null);
    try {
      const saved = await productEditorApi.save(value, reason);
      setNotice(`Сохранена версия ${saved.product.version}. Новые заявки используют эти условия.`);
      setDraft(null); setReason('');
      setConfirm(false);
      await history.reload(); await onSaved();
    } catch (next) {
      setConfirm(false);
      setError(next instanceof Error ? next.message : 'Не удалось сохранить продукт');
      await history.reload();
    }
    finally { setBusy(false); }
  };
  if (history.loading && !value) return <LoadingBlock />;
  if (!value) return <ErrorState message={history.error ?? 'Продукт не найден'} onRetry={history.reload} />;
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
            {history.error && <Alert severity="warning" action={<Button onClick={history.reload}>Повторить</Button>}>История не обновилась: {history.error}</Alert>}
            {error && <Alert severity="error">{error} Черновик сохранён в форме. Перед повторной записью откройте действующую версию в истории.</Alert>}
            {draft && draft.version !== current?.version && <Alert severity="warning" action={<Button disabled={busy} onClick={() => requestDiscard(() => { setDraft(null); setReason(''); setError(null); })}>Загрузить текущие условия</Button>}>Появилась новая версия. Просмотрите её в истории; загрузка текущих условий заменит ваш черновик.</Alert>}
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
              <Button variant="contained" disabled={busy || !reason.trim() || !changed} onClick={() => setConfirm(true)}>Сохранить новую версию продукта</Button>
            </>}
          </Stack>
        </Paper>
      </Grid>
      <Grid item xs={12} lg={4}>
        <VersionHistory title="История версий продукта" currentVersion={current?.version ?? value.version ?? 1}
          items={(history.data ?? []).map((item) => ({ ...item, version: item.product.version }))} onOpen={setViewedVersion} />
      </Grid>
      <SaveVersionDialog open={confirm} busy={busy} onCancel={() => setConfirm(false)} onConfirm={() => void save()} />
      {viewed && <VersionPreviewDialog title={`Версия ${viewed.product.version} · ${viewed.product.name}`} onClose={() => setViewedVersion(null)} metadata={{ ...viewed, version: viewed.product.version }}>
        <Stack spacing={1.5}>
          <Typography>{viewed.product.description}</Typography>
          <Typography>Ставка: {viewed.product.baseRate}%</Typography>
          <Typography>Сумма: {rub(viewed.product.minAmount)} — {rub(viewed.product.maxAmount)}</Typography>
          <Typography>Срок: {viewed.product.minTermMonths} — {viewed.product.maxTermMonths} месяцев</Typography>
          <Typography>Лимит автоодобрения: {rub(viewed.product.autoApprovalLimit)}</Typography>
          <Typography>{viewed.product.revolving ? 'Возобновляемый лимит' : 'Разовая выдача'} · {viewed.product.active ? 'В каталоге' : 'Выключен'}</Typography>
          <Typography>Цели: {viewed.product.purposes.map((item) => purposes.find((purpose) => purpose.code === item)?.title ?? item).join('; ')}</Typography>
        </Stack>
      </VersionPreviewDialog>}
    </Grid>
  );
}
