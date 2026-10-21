import { Alert, Box, Button, Card, CardActionArea, Chip, Grid, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import CheckCircleOutlineRoundedIcon from '@mui/icons-material/CheckCircleOutlineRounded';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { applicationsApi, productsApi } from '../../api/client';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { MoneyField } from '../../components/MoneyField';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { months, rub, rubExact } from '../../shared/format';
import { FinancingPurpose, ProductMatch } from '../../types';

const TERMS = [3, 6, 12, 18, 24, 36, 48, 60];

/**
 * The needs wizard: what the money is for, how much and for how long — and the products that
 * fit, with the reasons and the payment. Rules, not a model: every suggestion can be explained.
 */
export function NeedsWizardPage() {
  const navigate = useNavigate();
  const purposes = useApi(productsApi.purposes);
  const [purpose, setPurpose] = useState<FinancingPurpose | null>(null);
  const [amount, setAmount] = useState<number | null>(1_000_000);
  const [termMonths, setTermMonths] = useState(12);
  const [matches, setMatches] = useState<ProductMatch[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (purposes.loading) return <LoadingBlock />;
  if (purposes.error || !purposes.data) return <ErrorState message={purposes.error ?? 'Нет данных'} onRetry={purposes.reload} />;

  const search = async (event: FormEvent) => {
    event.preventDefault();
    if (!purpose || !amount) return;
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
        <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="h6" component="h2" gutterBottom>1. На что нужны деньги?</Typography>
          <Grid container spacing={1.5}>
            {purposes.data.map((item) => (
              <Grid item xs={12} sm={6} md={4} key={item.code}>
                <Card variant="outlined" sx={{ height: '100%', borderColor: purpose === item.code ? 'primary.main' : undefined, borderWidth: purpose === item.code ? 2 : 1 }}>
                  <CardActionArea sx={{ height: '100%', p: 2 }} aria-pressed={purpose === item.code} onClick={() => { setPurpose(item.code); setMatches(null); }}>
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
            <MoneyField label="Сумма" value={amount} onChange={(value) => { setAmount(value); setMatches(null); }} sx={{ minWidth: 240 }} required />
            <TextField select label="Срок" value={termMonths} onChange={(event) => { setTermMonths(Number(event.target.value)); setMatches(null); }} sx={{ minWidth: 200 }}>
              {TERMS.map((term) => <MenuItem key={term} value={term}>{months(term)}</MenuItem>)}
            </TextField>
            <Button type="submit" variant="contained" size="large" disabled={!purpose || !amount || busy} sx={{ minHeight: 56 }}>Подобрать</Button>
          </Stack>
          {!purpose && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Сначала выберите цель.</Typography>}
        </Paper>

        {error && <Alert severity="error">{error}</Alert>}

        {matches && (
          <Box>
            <Typography variant="h6" component="h2" gutterBottom>3. Подходящие продукты</Typography>
            {matches.length === 0 && <Alert severity="info">Для этой цели продуктов нет — попробуйте выбрать другую цель.</Alert>}
            <Stack spacing={2}>
              {matches.map((match) => {
                const amountFits = !!amount && amount >= match.product.minAmount && amount <= match.product.maxAmount;
                return (
                  <Paper key={match.product.code} variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                    <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between">
                      <Box sx={{ flex: 1 }}>
                        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                          <Typography variant="h6" component="h3">{match.product.name}</Typography>
                          {match.fits && <Chip size="small" color="success" label="Подходит" />}
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
                        <Button variant="contained" disabled={!amountFits || busy} onClick={() => start(match)}>
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
