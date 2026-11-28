import { Alert, Box, Button, Card, CardActionArea, Chip, Grid, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import CheckCircleOutlineRoundedIcon from '@mui/icons-material/CheckCircleOutlineRounded';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { applicationsApi, productsApi } from '../../api/client';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { Illustration } from '../../components/Illustration';
import { MoneyField } from '../../components/MoneyField';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { readFinancingSelection } from '../../shared/financingSelection';
import { months, rub, rubExact } from '../../shared/format';
import { purposeArt } from '../../shared/illustrations';
import { tokens } from '../../theme/theme';
import { FinancingPurpose, ProductMatch } from '../../types';

const TERMS = [3, 6, 12, 18, 24, 36, 48, 60];
const QUICK_AMOUNTS = [500_000, 1_000_000, 3_000_000, 5_000_000, 10_000_000];

/** A typical request for each goal: a cash gap is short and small, equipment is long and large. */
const PURPOSE_DEFAULTS: Record<FinancingPurpose, { amount: number; termMonths: number }> = {
  CASH_GAP: { amount: 500_000, termMonths: 3 },
  CURRENT_EXPENSES: { amount: 700_000, termMonths: 12 },
  WORKING_CAPITAL: { amount: 1_500_000, termMonths: 12 },
  EQUIPMENT: { amount: 3_000_000, termMonths: 36 },
  VEHICLE: { amount: 2_500_000, termMonths: 36 },
  RENOVATION: { amount: 2_000_000, termMonths: 24 },
  BUSINESS_DEVELOPMENT: { amount: 5_000_000, termMonths: 48 },
};

/**
 * The needs wizard: what the money is for, how much and for how long — and the products that
 * fit, with the reasons and the payment. Rules, not a model: every suggestion can be explained.
 */
export function NeedsWizardPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [selection] = useState(() => readFinancingSelection(location.search));
  const purposes = useApi(productsApi.purposes);
  const [purpose, setPurpose] = useState<FinancingPurpose | null>(selection.purpose ?? null);
  const [amount, setAmount] = useState<number | null>(selection.amount ?? 1_000_000);
  const [amountVisited, setAmountVisited] = useState(false);
  // If the client entered with their own amount/purpose or already ran a search, recalculate live.
  const [autoMatch, setAutoMatch] = useState(() => Boolean(selection.amount || selection.purpose || selection.product));
  // A term from a link (the assistant's «Оформить») snaps to the nearest one the form offers.
  const [termMonths, setTermMonths] = useState(() => {
    const wanted = selection.termMonths ?? 12;
    return TERMS.reduce((best, term) => (Math.abs(term - wanted) < Math.abs(best - wanted) ? term : best));
  });
  // Once the person types a sum or picks a term, a new goal no longer overwrites them.
  const [ownTerms, setOwnTerms] = useState(() => selection.amount != null || selection.termMonths != null);
  const [matches, setMatches] = useState<ProductMatch[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // When autoMatch is active (linked parameters or after the first search), update product matches automatically.
  const knownPurpose = purpose !== null && (purposes.data ?? []).some((item) => item.code === purpose);
  useEffect(() => {
    if (!autoMatch || !knownPurpose || !amount || amount <= 0) return;
    let active = true;
    const timer = window.setTimeout(() => {
      productsApi
        .match(purpose!, amount, termMonths)
        .then((next) => {
          if (active) {
            setMatches(next);
            setError(null);
          }
        })
        .catch(() => undefined);
    }, matches === null ? 0 : 250);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoMatch, knownPurpose, purpose, amount, termMonths]);

  if (purposes.loading) return <LoadingBlock />;
  if (purposes.error || !purposes.data) return <ErrorState message={purposes.error ?? 'Нет данных'} onRetry={purposes.reload} />;

  const amountInvalid = !amount || amount <= 0;

  const search = async (event: FormEvent) => {
    event.preventDefault();
    setAmountVisited(true);
    if (!purpose || amountInvalid) return;
    setAutoMatch(true);
    setBusy(true);
    setError(null);
    try {
      setMatches(await productsApi.match(purpose, amount, termMonths));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось подобрать продукты');
    } finally {
      setBusy(false);
    }
  };

  const start = async (match: ProductMatch) => {
    if (!purpose || !amount) return;
    setBusy(true);
    setError(null);
    try {
      const draft = await applicationsApi.create({ productCode: match.product.code, purpose, amount, termMonths: match.termMonths, bureauConsent: false });
      navigate(`/client/applications/${draft.id}`);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось создать заявку');
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Подбор финансирования" subtitle="Расскажите, зачем нужны деньги, — подскажем подходящий продукт и платёж" />
      <Stack spacing={3}>
        {(selection.purpose || selection.product || selection.amount) && (
          <Alert severity="info">
            {knownPurpose
              ? 'Ваш выбор перенесён. Подходящие продукты, лимиты и ежемесячный платёж рассчитаны ниже — при изменении суммы или срока расчёт обновится автоматически.'
              : 'Сумма перенесена. Выберите цель финансирования — сразу покажем подходящие продукты, лимиты и платёж.'}
          </Alert>
        )}
        <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="h6" component="h2" gutterBottom>1. На что нужны деньги?</Typography>
          <Grid container spacing={1.5}>
            {purposes.data.map((item) => (
              <Grid item xs={12} sm={6} md={4} key={item.code}>
                <Card elevation={0} sx={{ height: '100%', bgcolor: purpose === item.code ? '#fff' : tokens.inputFill, boxShadow: purpose === item.code ? `inset 0 0 0 2px ${tokens.red}` : 'none' }}>
                  <CardActionArea sx={{ height: '100%', p: 2, display: 'flex', alignItems: 'center', justifyContent: 'flex-start', gap: 1.5 }} aria-pressed={purpose === item.code}
                    onClick={() => {
                      setPurpose(item.code);
                      if (!autoMatch) setMatches(null);
                      const typical = PURPOSE_DEFAULTS[item.code];
                      if (!ownTerms && typical) { setAmount(typical.amount); setTermMonths(typical.termMonths); }
                    }}>
                    <Illustration name={purposeArt[item.code] ?? 'coin'} size={44} />
                    <Typography fontWeight={600}>{item.title}</Typography>
                  </CardActionArea>
                </Card>
              </Grid>
            ))}
          </Grid>
        </Paper>

        <Paper variant="outlined" component="form" onSubmit={search} sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="h6" component="h2" gutterBottom>2. Сколько и на какой срок?</Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'flex-start' }}>
            <MoneyField
              label="Сумма"
              value={amount}
              onChange={(value) => { setAmount(value); setOwnTerms(true); if (!autoMatch) setMatches(null); }}
              onBlur={() => setAmountVisited(true)}
              error={amountVisited && amountInvalid}
              helperText={amountVisited && amountInvalid ? 'Укажите сумму больше нуля' : 'Введите свою сумму или выберите быстрый вариант ниже'}
              sx={{ minWidth: 240 }}
              required
            />
            <TextField select label="Срок" value={termMonths} onChange={(event) => { setTermMonths(Number(event.target.value)); setOwnTerms(true); if (!autoMatch) setMatches(null); }} sx={{ minWidth: 200 }}>
              {[...new Set([...TERMS, termMonths])].sort((a, b) => a - b).map((term) => <MenuItem key={term} value={term}>{months(term)}</MenuItem>)}
            </TextField>
            <Button type="submit" variant="contained" size="large" disabled={!purpose || amountInvalid || busy} sx={{ minHeight: 56 }}>Подобрать</Button>
          </Stack>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1.5 }}>
            {QUICK_AMOUNTS.map((preset) => (
              <Chip
                key={preset}
                label={rub(preset)}
                variant={amount === preset ? 'filled' : 'outlined'}
                color={amount === preset ? 'primary' : 'default'}
                onClick={() => { setAmount(preset); setAmountVisited(true); setOwnTerms(true); }}
              />
            ))}
          </Stack>
          {!purpose && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Сначала выберите цель финансирования выше.</Typography>}
        </Paper>

        {error && <Alert severity="error">{error}</Alert>}

        {matches && (
          <Box>
            <Typography variant="h6" component="h2" gutterBottom>3. Подходящие продукты</Typography>
            {matches.length === 0 && <Alert severity="info">Для этой цели продуктов нет — попробуйте выбрать другую цель.</Alert>}
            <Stack spacing={2}>
              {[...matches].sort((a, b) => Number(b.product.code === selection.product) - Number(a.product.code === selection.product)).map((match) => {
                const amountFits = !!amount && amount >= match.product.minAmount && amount <= match.product.maxAmount;
                const clampedAmount = amount
                  ? Math.min(Math.max(amount, match.product.minAmount), match.product.maxAmount)
                  : match.product.minAmount;
                return (
                  <Paper key={match.product.code} variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                    <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between">
                      <Box sx={{ flex: 1 }}>
                        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                          <Typography variant="h6" component="h3">{match.product.name}</Typography>
                          {match.product.code === selection.product && <Chip size="small" label="Выбран на сайте" />}
                          {match.fits && <Chip size="small" color="success" label="Соответствует параметрам" />}
                        </Stack>
                        <Typography color="text.secondary" sx={{ mt: 0.5 }}>{match.product.description}</Typography>
                        <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                          {match.reasons.map((reason) => (
                            <Stack key={reason} direction="row" spacing={1} alignItems="flex-start">
                              <CheckCircleOutlineRoundedIcon fontSize="small" color="success" />
                              <Typography variant="body2">{reason}</Typography>
                            </Stack>
                          ))}
                          {match.adjustments.map((adjustment) => (
                            <Stack key={adjustment} direction="row" spacing={1} alignItems="flex-start">
                              <InfoOutlinedIcon fontSize="small" color="warning" />
                              <Typography variant="body2">{adjustment}</Typography>
                            </Stack>
                          ))}
                        </Stack>
                      </Box>
                      <Stack spacing={1} sx={{ minWidth: 240 }} alignItems={{ md: 'flex-end' }}>
                        <Typography variant="body2" color="text.secondary">
                          {match.product.revolving ? 'Минимальный платёж' : 'Платёж в месяц'} от
                        </Typography>
                        <Typography variant="h5" fontWeight={700}>{match.monthlyPayment != null ? rubExact(match.monthlyPayment) : '—'}</Typography>
                        <Typography variant="body2" color="text.secondary">
                          ставка от {match.product.baseRate}% · {months(match.termMonths)} · {rub(match.product.minAmount)} – {rub(match.product.maxAmount)}
                        </Typography>
                        {!amountFits && !!amount && (
                          <Typography variant="body2" color="warning.dark" role="status" sx={{ textAlign: { md: 'right' } }}>
                            {amount > match.product.maxAmount ? 'Сумма больше лимита продукта' : 'Сумма меньше минимума продукта'}
                          </Typography>
                        )}
                        {!amountFits && !!amount && (
                          <Button
                            size="small"
                            variant="outlined"
                            color="warning"
                            disabled={busy}
                            onClick={() => {
                              setAmount(clampedAmount);
                              setOwnTerms(true);
                              setAutoMatch(true);
                            }}
                          >
                            Установить {rub(clampedAmount)}
                          </Button>
                        )}
                        <Button variant="contained" color="secondary" disabled={!amountFits || busy} onClick={() => start(match)}>
                          {match.termMonths === termMonths ? 'Оформить заявку' : `Оформить на ${months(match.termMonths)}`}
                        </Button>
                      </Stack>
                    </Stack>
                  </Paper>
                );
              })}
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
              Платёж рассчитан по базовой ставке. Итоговая ставка зависит от оценки заявки и будет в предложении банка.
            </Typography>
          </Box>
        )}
      </Stack>
    </>
  );
}
