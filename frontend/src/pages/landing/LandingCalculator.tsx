import { Alert, Box, Button, MenuItem, Paper, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { productsApi } from '../../api/client';
import { MoneyField } from '../../components/MoneyField';
import { useApi } from '../../hooks/useApi';
import { months, percent, rubExact } from '../../shared/format';
import { financingPath } from '../../shared/financingSelection';
import { FinancingPurpose, ProductMatch } from '../../types';

const TERMS = [6, 12, 18, 24, 36, 48, 60];

/**
 * The first thing a visitor can do: say what the money is for and see the fitting product and
 * the payment, counted by the same rules as in the cabinet.
 */
export function LandingCalculator({ startPath, actionLabel = 'Оформить заявку онлайн' }: { startPath: string; actionLabel?: string }) {
  const purposes = useApi(productsApi.purposes);
  const [purpose, setPurpose] = useState<FinancingPurpose>('WORKING_CAPITAL');
  const [amount, setAmount] = useState<number | null>(2_000_000);
  const [termMonths, setTermMonths] = useState(24);
  const [best, setBest] = useState<ProductMatch | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setBest(undefined);
    setError(null);
    if (!amount || !Number.isFinite(amount) || amount <= 0) return;
    let current = true;
    const timer = window.setTimeout(() => {
      productsApi
        .match(purpose, amount, termMonths)
        .then((matches) => current && setBest(matches.find((match) => match.fits) ?? matches[0] ?? null))
        .then(() => current && setError(null))
        .catch((nextError: Error) => current && setError(nextError.message));
    }, 300);
    return () => {
      current = false;
      window.clearTimeout(timer);
    };
  }, [purpose, amount, termMonths]);

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, sm: 2.5, md: 4 }, minWidth: 0, borderRadius: { xs: '16px', sm: '32px' }, boxShadow: { xs: 'none', sm: '0 24px 64px rgba(15, 25, 55, 0.12)' }, border: 0 }}>
      <Typography variant="h6" component="h2" gutterBottom sx={{ display: { xs: 'none', sm: 'block' } }}>Рассчитайте кредит</Typography>
      <Stack spacing={2}>
        <TextField select label="На что нужны деньги" value={purpose} onChange={(event) => setPurpose(event.target.value as FinancingPurpose)}>
          {(purposes.data ?? []).map((item) => <MenuItem key={item.code} value={item.code}>{item.title}</MenuItem>)}
        </TextField>
        <MoneyField label="Сумма" value={amount} onChange={setAmount} />
        <TextField select label="Срок" value={termMonths} onChange={(event) => setTermMonths(Number(event.target.value))}>
          {TERMS.map((term) => <MenuItem key={term} value={term}>{months(term)}</MenuItem>)}
        </TextField>
      </Stack>
      <Box sx={{ mt: 3, minHeight: 112 }} aria-live="polite">
        {error && <Alert severity="error">{error}</Alert>}
        {!amount ? <Typography color="text.secondary">Введите сумму для расчёта.</Typography> : !error && best === undefined && <Skeleton variant="rounded" height={96} />}
        {!error && best === null && <Typography color="text.secondary">Для этой цели продукта нет — выберите другую.</Typography>}
        {!error && best && (
          <>
            <Typography variant="body2" color="text.secondary">{best.product.name} · ставка от {percent(best.product.baseRate)}</Typography>
            <Typography variant="h4" component="p" sx={{ mt: 0.5, fontSize: { xs: '1.75rem', sm: '2.5rem' } }}>
              {best.monthlyPayment != null ? rubExact(best.monthlyPayment) : '—'}
              <Typography component="span" color="text.secondary"> в месяц</Typography>
            </Typography>
            {!best.fits && <Typography variant="body2" color="warning.main" sx={{ mt: 0.5 }}>{best.adjustments.join('; ')}</Typography>}
          </>
        )}
      </Box>
      <Button component={RouterLink} to={financingPath(startPath, { purpose, amount: amount ?? undefined, termMonths: best?.termMonths ?? termMonths, product: best?.product.code })} disabled={!amount || !best || !!error} variant="contained" size="large" fullWidth sx={{ mt: 1 }}>{actionLabel}</Button>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>
        Платёж по базовой ставке. Итоговые условия — в предложении банка после оценки заявки.
      </Typography>
    </Paper>
  );
}
