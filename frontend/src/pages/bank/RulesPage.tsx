import { Alert, Button, Grid, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { rulesApi } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { SaveVersionDialog, VersionHistory, VersionPreviewDialog } from '../../components/VersionHistory';
import { useApi } from '../../hooks/useApi';
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges';
import { sameVersionParams } from '../../shared/versionParams';
import { RuleParams, RulesResponse } from '../../types';

const RATINGS = ['A', 'B', 'C', 'D'] as const;

/** Shares are stored as fractions and edited as percent. */
const toPercent = (fraction: number) => Math.round(fraction * 1000) / 10;
const toFraction = (percent: string) => Number(percent.replace(',', '.')) / 100;
const codes = (text: string) => text.split(/[\s,;]+/).map((item) => item.trim()).filter(Boolean);

/**
 * Thresholds of the credit policy. Every save is a new version: assessments from now on use it,
 * decisions already made keep theirs. Staff read, the risk administrator changes.
 */
export function RulesPage() {
  const { session } = useAuth();
  const canEdit = session?.user.role === 'RISK_ADMIN';
  const rules = useApi(rulesApi.current);
  const [draft, setDraft] = useState<RuleParams | null>(null);
  const [lowRisk, setLowRisk] = useState('');
  const [highRisk, setHighRisk] = useState('');
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [baseVersion, setBaseVersion] = useState(0);
  const [confirm, setConfirm] = useState(false);
  const [viewedVersion, setViewedVersion] = useState<number | null>(null);
  const candidate = draft ? { ...draft, lowRiskOkved: codes(lowRisk), highRiskOkved: codes(highRisk) } : null;
  const changed = !!candidate && !!rules.data && !sameVersionParams(candidate, rules.data.params);
  const guard = useUnsavedChanges(changed || !!comment.trim(), busy);

  useEffect(() => {
    if (!rules.data || draft) return;
    setDraft(rules.data.params);
    setBaseVersion(rules.data.version);
    setLowRisk(rules.data.params.lowRiskOkved.join(', '));
    setHighRisk(rules.data.params.highRiskOkved.join(', '));
  }, [rules.data, draft]);

  if (rules.loading && !rules.data) return <LoadingBlock />;
  if (!rules.data || !draft) return <ErrorState message={rules.error ?? 'Нет данных'} onRetry={rules.reload} />;

  const save = async () => {
    if (busy || !candidate || !changed || !comment.trim()) return;
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const next = await rulesApi.save(candidate, comment.trim(), baseVersion);
      setSaved(`Сохранена версия ${next.version}. Новые оценки идут по ней.`);
      setDraft(next.params);
      setLowRisk(next.params.lowRiskOkved.join(', '));
      setHighRisk(next.params.highRiskOkved.join(', '));
      setBaseVersion(next.version);
      setComment('');
      setConfirm(false);
      await rules.reload();
    } catch (nextError) {
      setConfirm(false);
      setError(nextError instanceof Error ? nextError.message : 'Не удалось сохранить');
      await rules.reload();
    } finally {
      setBusy(false);
    }
  };

  const percentField = (label: string, value: number, onChange: (fraction: number) => void, helper: string) => (
    <TextField label={label} value={toPercent(value)} disabled={!canEdit} helperText={helper} fullWidth
      InputProps={{ endAdornment: <Typography color="text.secondary">%</Typography> }}
      onChange={(event) => onChange(toFraction(event.target.value.replace(/[^\d.,]/g, '')))} />
  );

  return (
    <>
      <PageHeader title="Правила кредитной политики" subtitle={`Действует версия ${rules.data.version}. Баллы факторов заданы моделью скоринга, здесь — пороги, по которым из баллов получается решение.`} />
      {!canEdit && <Alert severity="info" sx={{ mb: 3 }}>Изменять правила может риск-администратор.</Alert>}
      <Grid container spacing={3}>
        <Grid item xs={12} lg={8}>
          <Stack spacing={3}>
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <Typography variant="h6" component="h2" gutterBottom>Нагрузка и выручка</Typography>
              <Grid container spacing={2}>
                <Grid item xs={12} sm={6}>{percentField('Предельная долговая нагрузка', draft.maxLoad, (maxLoad) => setDraft({ ...draft, maxLoad }), 'Все платежи по кредитам / месячная выручка')}</Grid>
                <Grid item xs={12} sm={6}>{percentField('Допустимое расхождение выручки', draft.revenueMismatch, (revenueMismatch) => setDraft({ ...draft, revenueMismatch }), 'Заявлено клиентом vs ФНС; больше — к андеррайтеру')}</Grid>
                <Grid item xs={12} sm={6}>{percentField('Рейтинг D: доля от запроса', draft.weakAmountShare, (weakAmountShare) => setDraft({ ...draft, weakAmountShare }), 'Не больше этой доли запрошенной суммы')}</Grid>
                <Grid item xs={12} sm={6}>{percentField('Рейтинг D: доля выручки', draft.weakRevenueShare, (weakRevenueShare) => setDraft({ ...draft, weakRevenueShare }), 'И не больше этой доли годовой выручки')}</Grid>
              </Grid>
            </Paper>
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <Typography variant="h6" component="h2" gutterBottom>Рейтинги</Typography>
              <Grid container spacing={2}>
                {RATINGS.map((rating) => (
                  <Grid item xs={6} md={3} key={rating}>
                    <Stack spacing={2}>
                      <TextField label={`${rating}: от баллов`} value={draft.ratingCutoffs[rating]} disabled={!canEdit} inputMode="numeric"
                        onChange={(event) => setDraft({ ...draft, ratingCutoffs: { ...draft.ratingCutoffs, [rating]: Number(event.target.value.replace(/\D/g, '')) || 0 } })} />
                      <TextField label={`${rating}: надбавка, п.п.`} value={draft.ratingMargins[rating]} disabled={!canEdit}
                        onChange={(event) => setDraft({ ...draft, ratingMargins: { ...draft.ratingMargins, [rating]: Number(event.target.value.replace(',', '.').replace(/[^\d.]/g, '')) || 0 } })} />
                    </Stack>
                  </Grid>
                ))}
              </Grid>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Ниже границы D — рейтинг E, отказ. Рейтинг C всегда уходит андеррайтеру.</Typography>
            </Paper>
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <Typography variant="h6" component="h2" gutterBottom>Отраслевой риск (классы ОКВЭД)</Typography>
              <Stack spacing={2}>
                <TextField label="Низкий риск" value={lowRisk} onChange={(event) => setLowRisk(event.target.value)} disabled={!canEdit} multiline helperText="Две цифры через запятую, например 01, 10, 62" />
                <TextField label="Высокий риск — всегда к андеррайтеру" value={highRisk} onChange={(event) => setHighRisk(event.target.value)} disabled={!canEdit} multiline helperText="Остальные классы — средний риск" />
              </Stack>
            </Paper>
            {canEdit && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                <TextField label="Что и зачем меняется" fullWidth multiline minRows={2} value={comment} onChange={(event) => setComment(event.target.value)} inputProps={{ maxLength: 1000 }}
                  helperText="Попадёт в историю версий" required />
                {error && <Alert severity="error" sx={{ mt: 2 }}>{error}. Черновик остался в форме.
                  <Button onClick={() => guard.request(() => { setDraft(null); setComment(''); setError(null); })}>Загрузить текущие условия</Button>
                </Alert>}
                {saved && <Alert severity="success" sx={{ mt: 2 }}>{saved}</Alert>}
                <Button variant="contained" size="large" sx={{ mt: 2 }} disabled={busy || !comment.trim() || !changed} onClick={() => setConfirm(true)}>Сохранить новую версию</Button>
              </Paper>
            )}
          </Stack>
        </Grid>
        <Grid item xs={12} lg={4}>
          <VersionHistory title="История версий правил" items={rules.data.versions.map(ruleMetadata)} currentVersion={rules.data.version} onOpen={setViewedVersion} />
        </Grid>
      </Grid>
      <SaveVersionDialog open={confirm} busy={busy} onCancel={() => setConfirm(false)} onConfirm={() => void save()} />
      {viewedVersion !== null && <RuleSnapshotDialog version={viewedVersion} onClose={() => setViewedVersion(null)} />}
      {guard.dialog}
    </>
  );
}

const ruleMetadata = (item: RulesResponse['versions'][number]) => ({
  version: item.version, createdAt: item.createdAt, authorId: item.createdBy,
  authorName: item.authorName, authorRole: item.authorRole, reason: item.comment
});

function RuleSnapshotDialog({ version, onClose }: { version: number; onClose: () => void }) {
  const loaded = useApi(() => rulesApi.version(version), [version]);
  const p = loaded.data?.params;
  return <VersionPreviewDialog title={`Правила · версия ${version}`} onClose={onClose} metadata={loaded.data ? ruleMetadata(loaded.data.metadata) : undefined}>
    {loaded.loading && <LoadingBlock />}
    {loaded.error && <ErrorState message={loaded.error} onRetry={loaded.reload} />}
    {p && <Stack spacing={1.5}>
      <Typography>Предельная долговая нагрузка: {toPercent(p.maxLoad)}%</Typography>
      <Typography>Допустимое расхождение выручки: {toPercent(p.revenueMismatch)}%</Typography>
      <Typography>Рейтинг D: до {toPercent(p.weakAmountShare)}% запроса и {toPercent(p.weakRevenueShare)}% годовой выручки</Typography>
      {RATINGS.map((rating) => <Typography key={rating}>Рейтинг {rating}: от {p.ratingCutoffs[rating]} баллов · надбавка {p.ratingMargins[rating]} п.п.</Typography>)}
      <Typography>Низкий отраслевой риск: {p.lowRiskOkved.join(', ') || 'Не задан'}</Typography>
      <Typography>Высокий отраслевой риск: {p.highRiskOkved.join(', ') || 'Не задан'}</Typography>
    </Stack>}
  </VersionPreviewDialog>;
}
