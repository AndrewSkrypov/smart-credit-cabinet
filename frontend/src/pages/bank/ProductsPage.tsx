import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Grid,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import { useEffect, useState } from 'react';
import { productEditorApi, productsApi } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { SaveVersionDialog, VersionHistory, VersionPreviewDialog } from '../../components/VersionHistory';
import { ProductSnapshot } from '../../components/VersionSnapshots';
import { useApi } from '../../hooks/useApi';
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges';
import { sameVersionParams } from '../../shared/versionParams';
import { FinancingPurpose, Product, ProductCreateInput, ProductLifecycleStatus } from '../../types';

const lifecycleMeta: Record<ProductLifecycleStatus, { label: string; color: 'warning' | 'success' | 'default' }> = {
  DRAFT: { label: 'Черновик', color: 'warning' },
  ACTIVE: { label: 'Активен', color: 'success' },
  ARCHIVED: { label: 'В архиве', color: 'default' }
};

function resolveLifecycle(product: Product): ProductLifecycleStatus {
  if (product.lifecycleStatus) return product.lifecycleStatus;
  return product.active === false ? 'ARCHIVED' : 'ACTIVE';
}

function formatCommissionPercent(bps?: number): string {
  const value = (bps ?? 150) / 100;
  return `${value.toFixed(2).replace('.', ',')}%`;
}

function isoToDateInput(iso?: string | null): string {
  if (!iso) return '';
  return iso.slice(0, 10);
}

function dateInputToIso(dateStr: string, endOfDay = false): string | null {
  const trimmed = dateStr.trim();
  if (!trimmed) return null;
  return `${trimmed}T${endOfDay ? '23:59:59Z' : '00:00:00Z'}`;
}

export function ProductsPage() {
  const { session } = useAuth();
  const canEdit = session?.user.role === 'RISK_ADMIN';
  const products = useApi(productEditorApi.list);
  const purposes = useApi(productsApi.purposes);
  const [selected, setSelected] = useState('');
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const guard = useUnsavedChanges(dirty, busy);
  const code = selected || products.data?.[0]?.code || '';

  return (
    <>
      <PageHeader
        title="Продукты"
        subtitle="Условия каталога, жизненный цикл и история изменений. Поданные заявки сохраняют прежние условия."
        actions={
          canEdit ? (
            <Button
              variant="contained"
              startIcon={<AddRoundedIcon />}
              onClick={() => guard.request(() => setCreateOpen(true))}
              sx={{ alignSelf: { xs: 'flex-start', md: 'center' }, whiteSpace: 'nowrap' }}
            >
              Создать продукт (черновик)
            </Button>
          ) : undefined
        }
      />
      {products.loading && !products.data ? <LoadingBlock /> : products.error ? <ErrorState message={products.error} onRetry={products.reload} /> : (
        <Stack spacing={3}>
          <Paper sx={{ p: { xs: 2, md: 4 } }}>
            <TextField
              select
              label="Продукт"
              fullWidth
              value={code}
              onChange={(event) => {
                const next = event.target.value;
                guard.request(() => { setDirty(false); setSelected(next); });
              }}
            >
              {products.data?.map((product) => {
                const status = resolveLifecycle(product);
                return (
                  <MenuItem key={product.code} value={product.code}>
                    {product.name} · {lifecycleMeta[status].label}
                  </MenuItem>
                );
              })}
            </TextField>
          </Paper>
          {code && (
            <Box>
              <ProductCard
                key={code}
                code={code}
                purposes={purposes.data ?? []}
                onSaved={products.reload}
                onDirty={setDirty}
                onBusy={setBusy}
                requestDiscard={guard.request}
              />
            </Box>
          )}
        </Stack>
      )}
      <CreateProductDialog
        open={createOpen}
        purposes={purposes.data ?? []}
        onClose={() => setCreateOpen(false)}
        onCreated={async (createdCode) => {
          setCreateOpen(false);
          await products.reload();
          setDirty(false);
          setSelected(createdCode);
        }}
      />
      {guard.dialog}
    </>
  );
}

type LifecycleAction = 'publish' | 'archive' | 'restore';

const lifecycleActionCopy: Record<LifecycleAction, { title: string; button: string; description: string; success: string }> = {
  publish: {
    title: 'Опубликовать продукт в каталоге?',
    button: 'Опубликовать',
    description: 'Продукт перейдёт из черновика в статус «Активен» и станет доступен клиентам и агентам.',
    success: 'Продукт опубликован и доступен в каталоге.'
  },
  archive: {
    title: 'Отправить продукт в архив?',
    button: 'В архив',
    description: 'Новые заявки по этому продукту будут недоступны. Уже поданные заявки сохранят свои условия.',
    success: 'Продукт переведён в архив.'
  },
  restore: {
    title: 'Восстановить продукт из архива?',
    button: 'Восстановить',
    description: 'Продукт снова перейдёт в статус «Активен» и появится в каталоге.',
    success: 'Продукт восстановлен из архива.'
  }
};

function ProductCard({
  code,
  purposes,
  onSaved,
  onDirty,
  onBusy,
  requestDiscard
}: {
  code: string;
  purposes: { code: FinancingPurpose; title: string }[];
  onSaved: () => Promise<void>;
  onDirty: (dirty: boolean) => void;
  onBusy: (busy: boolean) => void;
  requestDiscard: (action: () => void) => void;
}) {
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
  const [lifecycleAction, setLifecycleAction] = useState<LifecycleAction | null>(null);
  const [lifecycleReason, setLifecycleReason] = useState('');
  const [commissionInput, setCommissionInput] = useState<string | null>(null);

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
      setDraft(null); setCommissionInput(null); setReason('');
      setConfirm(false);
      await history.reload(); await onSaved();
    } catch (next) {
      setConfirm(false);
      setError(next instanceof Error ? next.message : 'Не удалось сохранить продукт');
      await history.reload();
    } finally {
      setBusy(false);
    }
  };

  const runLifecycleTransition = async () => {
    if (!current || !lifecycleAction || !lifecycleReason.trim() || busy) return;
    const expectedVersion = current.version ?? 1;
    setBusy(true); setError(null); setNotice(null);
    try {
      if (lifecycleAction === 'publish') {
        await productEditorApi.publish(code, expectedVersion, lifecycleReason.trim());
      } else if (lifecycleAction === 'archive') {
        await productEditorApi.archive(code, expectedVersion, lifecycleReason.trim());
      } else {
        await productEditorApi.restore(code, expectedVersion, lifecycleReason.trim());
      }
      setNotice(lifecycleActionCopy[lifecycleAction].success);
      setLifecycleAction(null);
      setLifecycleReason('');
      setDraft(null);
      setCommissionInput(null);
      await history.reload();
      await onSaved();
    } catch (next) {
      setLifecycleAction(null);
      setError(next instanceof Error ? next.message : 'Не удалось изменить статус продукта');
      await history.reload();
    } finally {
      setBusy(false);
    }
  };

  if (history.loading && !value) return <LoadingBlock />;
  if (!value) return <ErrorState message={history.error ?? 'Продукт не найден'} onRetry={history.reload} />;

  const status = resolveLifecycle(value);
  const currentStatus = current ? resolveLifecycle(current) : status;
  const displayedCommission = commissionInput ?? ((value.agentCommissionRateBps ?? 150) / 100).toFixed(2);

  const numberField = (key: 'minAmount' | 'maxAmount' | 'minTermMonths' | 'maxTermMonths' | 'baseRate' | 'autoApprovalLimit', label: string) => (
    <Grid item xs={12} sm={6} key={key}>
      <TextField
        label={label}
        fullWidth
        type="number"
        value={value[key]}
        disabled={!canEdit || busy}
        inputProps={{ min: 0, step: key === 'baseRate' ? '0.01' : '1' }}
        onChange={(event) => change({ [key]: Number(event.target.value) })}
      />
    </Grid>
  );

  return (
    <Grid container spacing={3}>
      <Grid item xs={12} lg={8}>
        <Paper sx={{ p: { xs: 2, md: 4 } }}>
          <Stack spacing={2.5}>
            <Typography variant="h6">Условия продукта</Typography>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              <Chip label={`Версия ${value.version}`} />
              <Chip label={lifecycleMeta[status].label} color={lifecycleMeta[status].color} />
              <Chip variant="outlined" label={`Комиссия агента: ${formatCommissionPercent(value.agentCommissionRateBps)}`} />
            </Stack>

            {canEdit && (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} flexWrap="wrap" useFlexGap>
                {currentStatus === 'DRAFT' && (
                  <Button
                    variant="contained"
                    color="success"
                    size="small"
                    disabled={busy}
                    onClick={() => { setLifecycleReason(''); setLifecycleAction('publish'); }}
                  >
                    Опубликовать
                  </Button>
                )}
                {(currentStatus === 'DRAFT' || currentStatus === 'ACTIVE') && (
                  <Button
                    variant="outlined"
                    color="warning"
                    size="small"
                    disabled={busy}
                    onClick={() => { setLifecycleReason(''); setLifecycleAction('archive'); }}
                  >
                    В архив
                  </Button>
                )}
                {currentStatus === 'ARCHIVED' && (
                  <Button
                    variant="contained"
                    size="small"
                    disabled={busy}
                    onClick={() => { setLifecycleReason(''); setLifecycleAction('restore'); }}
                  >
                    Восстановить
                  </Button>
                )}
              </Stack>
            )}

            {!canEdit && <Alert severity="info">Изменять продукты может риск-администратор. Вам доступен просмотр условий и истории.</Alert>}
            {notice && <Alert severity="success">{notice}</Alert>}
            {history.error && <Alert severity="warning" action={<Button onClick={history.reload}>Повторить</Button>}>История не обновилась: {history.error}</Alert>}
            {error && <Alert severity="error">{error} Черновик сохранён в форме. Перед повторной записью откройте действующую версию в истории.</Alert>}
            {draft && draft.version !== current?.version && (
              <Alert
                severity="warning"
                action={
                  <Button disabled={busy} onClick={() => requestDiscard(() => { setDraft(null); setCommissionInput(null); setReason(''); setError(null); })}>
                    Загрузить текущие условия
                  </Button>
                }
              >
                Появилась новая версия. Просмотрите её в истории; загрузка текущих условий заменит ваш черновик.
              </Alert>
            )}
            <TextField label="Название продукта" value={value.name} disabled={!canEdit || busy} inputProps={{ maxLength: 255 }} onChange={(event) => change({ name: event.target.value })} />
            <TextField label="Описание продукта" multiline minRows={3} value={value.description} disabled={!canEdit || busy} inputProps={{ maxLength: 5000 }} onChange={(event) => change({ description: event.target.value })} />
            <Box>
              <Grid container spacing={2}>
                {numberField('baseRate', 'Базовая ставка, %')}
                {numberField('autoApprovalLimit', 'Лимит автоодобрения, ₽')}
                {numberField('minAmount', 'Минимальная сумма, ₽')}
                {numberField('maxAmount', 'Максимальная сумма, ₽')}
                {numberField('minTermMonths', 'Минимальный срок, месяцев')}
                {numberField('maxTermMonths', 'Максимальный срок, месяцев')}
                <Grid item xs={12} sm={6}>
                  <TextField
                    label="Комиссия агента, %"
                    fullWidth
                    type="number"
                    value={displayedCommission}
                    disabled={!canEdit || busy}
                    inputProps={{ min: 0, max: 100, step: '0.05' }}
                    helperText={`${value.agentCommissionRateBps ?? 150} б.п. (например, 1,50% = 150 б.п.)`}
                    onChange={(event) => {
                      const raw = event.target.value;
                      setCommissionInput(raw);
                      if (raw.trim() === '') return;
                      const parsed = Number(raw.replace(',', '.'));
                      if (Number.isFinite(parsed)) {
                        const clamped = Math.min(100, Math.max(0, parsed));
                        change({ agentCommissionRateBps: Math.round(clamped * 100) });
                      }
                    }}
                    onBlur={() => {
                      if (commissionInput === null) return;
                      const parsed = Number(commissionInput.replace(',', '.'));
                      if (commissionInput.trim() !== '' && Number.isFinite(parsed)) {
                        const clamped = Math.min(100, Math.max(0, parsed));
                        change({ agentCommissionRateBps: Math.round(clamped * 100) });
                      }
                      setCommissionInput(null);
                    }}
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    label="Действует с"
                    type="date"
                    fullWidth
                    InputLabelProps={{ shrink: true }}
                    value={isoToDateInput(value.effectiveFrom)}
                    disabled={!canEdit || busy}
                    onChange={(event) => change({ effectiveFrom: dateInputToIso(event.target.value, false) })}
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    label="Действует по"
                    type="date"
                    fullWidth
                    InputLabelProps={{ shrink: true }}
                    value={isoToDateInput(value.effectiveTo)}
                    disabled={!canEdit || busy}
                    onChange={(event) => change({ effectiveTo: dateInputToIso(event.target.value, true) })}
                  />
                </Grid>
              </Grid>
            </Box>
            <Typography fontWeight={600}>Цели финансирования</Typography>
            <Stack sx={{ '& .MuiFormControlLabel-root': { m: 0, minHeight: 44, alignItems: 'flex-start' }, '& .MuiFormControlLabel-label': { py: '10px', overflowWrap: 'anywhere' } }}>
              {purposes.map((purpose) => (
                <FormControlLabel
                  key={purpose.code}
                  label={purpose.title}
                  control={
                    <Checkbox
                      disabled={!canEdit || busy}
                      checked={value.purposes.includes(purpose.code)}
                      onChange={(_, checked) =>
                        change({
                          purposes: checked ? [...value.purposes, purpose.code] : value.purposes.filter((item) => item !== purpose.code)
                        })
                      }
                    />
                  }
                />
              ))}
            </Stack>
            <Typography fontWeight={600}>Параметры продукта</Typography>
            <Stack sx={{ '& .MuiFormControlLabel-root': { m: 0, minHeight: 44, alignItems: 'flex-start' }, '& .MuiFormControlLabel-label': { py: '10px', overflowWrap: 'anywhere' } }}>
              <FormControlLabel
                label="Возобновляемый лимит"
                control={<Checkbox disabled={!canEdit || busy} checked={value.revolving} onChange={(_, checked) => change({ revolving: checked })} />}
              />
            </Stack>
            {canEdit && (
              <>
                <TextField
                  label="Причина изменения продукта"
                  required
                  multiline
                  minRows={2}
                  inputProps={{ maxLength: 1000 }}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  disabled={busy}
                />
                <Button variant="contained" disabled={busy || !reason.trim() || !changed} onClick={() => setConfirm(true)}>
                  Сохранить новую версию продукта
                </Button>
              </>
            )}
          </Stack>
        </Paper>
      </Grid>
      <Grid item xs={12} lg={4}>
        <VersionHistory
          title="История версий продукта"
          currentVersion={current?.version ?? value.version ?? 1}
          items={(history.data ?? []).map((item) => ({ ...item, version: item.product.version }))}
          onOpen={setViewedVersion}
        />
      </Grid>

      <SaveVersionDialog open={confirm} busy={busy} onCancel={() => setConfirm(false)} onConfirm={() => void save()} />

      <Dialog open={lifecycleAction !== null} onClose={() => !busy && setLifecycleAction(null)} fullWidth maxWidth="sm">
        {lifecycleAction && (
          <>
            <DialogTitle>{lifecycleActionCopy[lifecycleAction].title}</DialogTitle>
            <DialogContent>
              <Typography color="text.secondary" sx={{ mb: 2 }}>
                {lifecycleActionCopy[lifecycleAction].description}
              </Typography>
              <TextField
                label="Причина изменения статуса"
                required
                fullWidth
                multiline
                minRows={2}
                value={lifecycleReason}
                onChange={(event) => setLifecycleReason(event.target.value)}
                inputProps={{ maxLength: 1000 }}
                disabled={busy}
              />
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setLifecycleAction(null)} disabled={busy}>Отмена</Button>
              <Button
                variant="contained"
                color={lifecycleAction === 'archive' ? 'warning' : 'primary'}
                disabled={busy || !lifecycleReason.trim()}
                onClick={() => void runLifecycleTransition()}
              >
                {busy ? 'Сохраняем…' : lifecycleActionCopy[lifecycleAction].button}
              </Button>
            </DialogActions>
          </>
        )}
      </Dialog>

      {viewed && (
        <VersionPreviewDialog
          title={`Версия ${viewed.product.version} · ${viewed.product.name}`}
          currentVersion={current?.version}
          onClose={() => setViewedVersion(null)}
          metadata={{ ...viewed, version: viewed.product.version }}
        >
          <ProductSnapshot product={viewed.product} purposes={purposes} />
        </VersionPreviewDialog>
      )}
    </Grid>
  );
}

function CreateProductDialog({
  open,
  purposes,
  onClose,
  onCreated
}: {
  open: boolean;
  purposes: { code: FinancingPurpose; title: string }[];
  onClose: () => void;
  onCreated: (code: string) => Promise<void>;
}) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [baseRate, setBaseRate] = useState(17.5);
  const [autoApprovalLimit, setAutoApprovalLimit] = useState(1_500_000);
  const [minAmount, setMinAmount] = useState(300_000);
  const [maxAmount, setMaxAmount] = useState(5_000_000);
  const [minTermMonths, setMinTermMonths] = useState(3);
  const [maxTermMonths, setMaxTermMonths] = useState(36);
  const [commissionPct, setCommissionPct] = useState('1.50');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [effectiveTo, setEffectiveTo] = useState('');
  const [revolving, setRevolving] = useState(false);
  const [selectedPurposes, setSelectedPurposes] = useState<FinancingPurpose[]>(['WORKING_CAPITAL']);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit =
    code.trim().length > 0 &&
    name.trim().length > 0 &&
    description.trim().length > 0 &&
    selectedPurposes.length > 0 &&
    reason.trim().length > 0 &&
    !busy;

  const submit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const pct = Number(commissionPct);
      const input: ProductCreateInput = {
        code: code.trim().toUpperCase(),
        name: name.trim(),
        description: description.trim(),
        purposes: selectedPurposes,
        minAmount,
        maxAmount,
        minTermMonths,
        maxTermMonths,
        baseRate,
        autoApprovalLimit,
        revolving,
        effectiveFrom: dateInputToIso(effectiveFrom, false),
        effectiveTo: dateInputToIso(effectiveTo, true),
        agentCommissionRateBps: Number.isFinite(pct) && pct >= 0 ? Math.round(pct * 100) : 150,
        reason: reason.trim()
      };
      const created = await productEditorApi.create(input);
      setCode('');
      setName('');
      setDescription('');
      setReason('');
      await onCreated(created.product.code);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось создать продукт');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={() => !busy && onClose()} fullWidth maxWidth="md">
      <DialogTitle>Создать продукт (черновик)</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Grid container spacing={2}>
            <Grid item xs={12} sm={4}>
              <TextField label="Код продукта" required fullWidth value={code} onChange={(e) => setCode(e.target.value)} placeholder="EXPRESS_2026" disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={8}>
              <TextField label="Название продукта" required fullWidth value={name} onChange={(e) => setName(e.target.value)} disabled={busy} />
            </Grid>
            <Grid item xs={12}>
              <TextField label="Описание" required fullWidth multiline minRows={2} value={description} onChange={(e) => setDescription(e.target.value)} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Базовая ставка, %" type="number" fullWidth value={baseRate} onChange={(e) => setBaseRate(Number(e.target.value))} inputProps={{ min: 0, step: '0.01' }} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Комиссия агента, %" type="number" fullWidth value={commissionPct} onChange={(e) => setCommissionPct(e.target.value)} inputProps={{ min: 0, max: 100, step: '0.05' }} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Минимальная сумма, ₽" type="number" fullWidth value={minAmount} onChange={(e) => setMinAmount(Number(e.target.value))} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Максимальная сумма, ₽" type="number" fullWidth value={maxAmount} onChange={(e) => setMaxAmount(Number(e.target.value))} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Минимальный срок, мес." type="number" fullWidth value={minTermMonths} onChange={(e) => setMinTermMonths(Number(e.target.value))} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Максимальный срок, мес." type="number" fullWidth value={maxTermMonths} onChange={(e) => setMaxTermMonths(Number(e.target.value))} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Лимит автоодобрения, ₽" type="number" fullWidth value={autoApprovalLimit} onChange={(e) => setAutoApprovalLimit(Number(e.target.value))} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={3}>
              <TextField label="Действует с" type="date" fullWidth InputLabelProps={{ shrink: true }} value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} disabled={busy} />
            </Grid>
            <Grid item xs={12} sm={3}>
              <TextField label="Действует по" type="date" fullWidth InputLabelProps={{ shrink: true }} value={effectiveTo} onChange={(e) => setEffectiveTo(e.target.value)} disabled={busy} />
            </Grid>
          </Grid>
          <Typography fontWeight={600}>Цели финансирования</Typography>
          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1}>
            {purposes.map((purpose) => (
              <FormControlLabel
                key={purpose.code}
                label={purpose.title}
                control={
                  <Checkbox
                    disabled={busy}
                    checked={selectedPurposes.includes(purpose.code)}
                    onChange={(_, checked) =>
                      setSelectedPurposes((prev) => (checked ? [...prev, purpose.code] : prev.filter((item) => item !== purpose.code)))
                    }
                  />
                }
              />
            ))}
          </Stack>
          <FormControlLabel
            label="Возобновляемый лимит"
            control={<Checkbox disabled={busy} checked={revolving} onChange={(_, checked) => setRevolving(checked)} />}
          />
          <TextField
            label="Причина создания продукта"
            required
            fullWidth
            multiline
            minRows={2}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={busy}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>Отмена</Button>
        <Button variant="contained" disabled={!canSubmit} onClick={() => void submit()}>
          {busy ? 'Создаём…' : 'Создать черновик'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
