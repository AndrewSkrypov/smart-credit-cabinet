import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import AutoAwesomeRoundedIcon from '@mui/icons-material/AutoAwesomeRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { agentApi, productsApi } from '../../api/client';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { MoneyField } from '../../components/MoneyField';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { agentNextAction, statusTitle, statusTone } from '../../shared/applicationText';
import { formatDate, formatDateTime, months, percent, rub } from '../../shared/format';
import { legalFormLabels } from '../../shared/labels';
import { FinancingPurpose } from '../../types';

export function AgentClientPage() {
  const { companyId = '' } = useParams();
  const navigate = useNavigate();

  const load = useCallback(async () => {
    const [clients, offers, applications, products, purposes] = await Promise.all([
      agentApi.clients(),
      agentApi.preApprovedOffers(),
      agentApi.applications(),
      productsApi.list(),
      productsApi.purposes()
    ]);
    return { clients, offers, applications, products, purposes };
  }, []);

  const state = useApi(load, [companyId]);

  const [productCode, setProductCode] = useState('');
  const [purpose, setPurpose] = useState<FinancingPurpose>('WORKING_CAPITAL');
  const [amount, setAmount] = useState<number | null>(1_000_000);
  const [termMonths, setTermMonths] = useState<number>(12);
  const [purposeComment, setPurposeComment] = useState('');
  const [declaredRevenue12m, setDeclaredRevenue12m] = useState<number | null>(null);
  const [declaredMonthlyDebtPayments, setDeclaredMonthlyDebtPayments] = useState<number | null>(0);
  const [revenueVisited, setRevenueVisited] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [applyingOfferId, setApplyingOfferId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const firstProduct = state.data?.products.find((item) => item.active !== false);
    if (firstProduct && !productCode) {
      setProductCode(firstProduct.code);
      if (firstProduct.purposes.length > 0) setPurpose(firstProduct.purposes[0]);
      setAmount(Math.min(Math.max(1_000_000, firstProduct.minAmount), firstProduct.maxAmount));
      setTermMonths(Math.min(Math.max(12, firstProduct.minTermMonths), firstProduct.maxTermMonths));
    }
  }, [state.data?.products, productCode]);

  if (state.loading && !state.data) return <LoadingBlock />;
  if (!state.data) return <ErrorState message={state.error ?? 'Не удалось загрузить данные клиента'} onRetry={state.reload} />;

  const client = state.data.clients.find((item) => item.companyId === companyId);
  if (!client) {
    return (
      <Stack spacing={2}>
        <Button component={RouterLink} to="/agent" startIcon={<ArrowBackRoundedIcon />} sx={{ alignSelf: 'flex-start' }}>
          Портфель
        </Button>
        <ErrorState message="Компания не найдена среди ваших закреплённых клиентов." />
      </Stack>
    );
  }

  const companyOffers = state.data.offers.filter((offer) => offer.companyId === companyId && offer.status === 'ACTIVE');
  const companyApps = state.data.applications.filter((app) => app.companyInn === client.inn);
  const activeProducts = state.data.products.filter((item) => item.active !== false);
  const selectedProduct = activeProducts.find((item) => item.code === productCode) ?? activeProducts[0];

  const amountMissing = !amount || amount <= 0;
  const termMissing = !termMonths || termMonths < 1;
  const amountOutside = Boolean(selectedProduct && !amountMissing && (amount < selectedProduct.minAmount || amount > selectedProduct.maxAmount));
  const termOutside = Boolean(selectedProduct && !termMissing && (termMonths < selectedProduct.minTermMonths || termMonths > selectedProduct.maxTermMonths));
  const revenueMissing = declaredRevenue12m == null || declaredRevenue12m <= 0;

  const handleApplyOffer = async (offerId: string) => {
    setApplyingOfferId(offerId);
    setError(null);
    try {
      const created = await agentApi.applyPreApprovedOffer(offerId);
      navigate(`/agent/applications/${created.id}`);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось оформить предодобренное предложение');
      setApplyingOfferId(null);
    }
  };

  const handleCreate = async (event: FormEvent) => {
    event.preventDefault();
    setRevenueVisited(true);
    if (!selectedProduct || amountMissing || termMissing || amountOutside || termOutside) {
      setError('Проверьте сумму и срок: значения должны входить в допустимые границы выбранного продукта.');
      return;
    }
    if (revenueMissing) {
      setError('Укажите выручку компании за последние 12 месяцев — это обязательный параметр для оценки заявки.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const created = await agentApi.createApplication({
        companyId: client.companyId,
        productCode: selectedProduct.code,
        purpose,
        purposeComment: purposeComment.trim() || null,
        amount,
        termMonths,
        declaredRevenue12m,
        declaredMonthlyDebtPayments
      });
      navigate(`/agent/applications/${created.id}`);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось создать черновик заявки');
      setSubmitting(false);
    }
  };

  return (
    <Stack spacing={3}>
      <Button component={RouterLink} to="/agent" startIcon={<ArrowBackRoundedIcon />} sx={{ alignSelf: 'flex-start' }}>
        Портфель агента
      </Button>

      <PageHeader
        title={client.name ?? `ИНН ${client.inn}`}
        subtitle={`${legalFormLabels[client.legalForm]} · ИНН ${client.inn} · закреплён ${formatDate(client.assignedAt)}`}
      />

      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

      {companyOffers.length > 0 && (
        <Box data-tour="agent-client-offers">
          <Typography variant="h6" component="h2" sx={{ mb: 1.5 }}>Предодобренные предложения</Typography>
          <Grid container spacing={2}>
            {companyOffers.map((offer) => (
              <Grid item xs={12} md={6} key={offer.id}>
                <Card variant="outlined" sx={{ borderColor: 'success.main' }}>
                  <CardContent>
                    <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
                      <Typography variant="subtitle1" fontWeight={700} sx={{ overflowWrap: 'anywhere' }}>{offer.productName}</Typography>
                      <Chip size="small" color="success" icon={<AutoAwesomeRoundedIcon />} label={`Ставка ${percent(offer.rate)}`} />
                    </Stack>
                    <Typography variant="h5" fontWeight={700} sx={{ mt: 1 }}>до {rub(offer.maxAmount)}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                      Срок от {months(offer.minTermMonths)} до {months(offer.maxTermMonths)} · действует до {formatDate(offer.validUntil)}
                    </Typography>
                    <Button
                      variant="contained"
                      sx={{ mt: 2 }}
                      disabled={applyingOfferId === offer.id}
                      onClick={() => void handleApplyOffer(offer.id)}
                    >
                      {applyingOfferId === offer.id ? 'Создаём черновик…' : 'Оформить'}
                    </Button>
                  </CardContent>
                </Card>
              </Grid>
            ))}
          </Grid>
        </Box>
      )}

      <Grid container spacing={3}>
        <Grid item xs={12} md={7}>
          <Paper data-tour="agent-client-new" variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
            <Typography variant="h6" component="h2" gutterBottom>Создать новую заявку</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              Черновик создаётся от вашего имени. После заполнения и загрузки документов отправьте его клиенту на подтверждение и согласие БКИ.
            </Typography>
            <Stack component="form" spacing={2} onSubmit={handleCreate}>
              <TextField
                select
                label="Кредитный продукт"
                value={selectedProduct?.code ?? ''}
                onChange={(event) => {
                  const next = activeProducts.find((item) => item.code === event.target.value);
                  setProductCode(event.target.value);
                  if (next) {
                    if (next.purposes.length > 0 && !next.purposes.includes(purpose)) setPurpose(next.purposes[0]);
                    if (amount && (amount < next.minAmount || amount > next.maxAmount)) setAmount(next.minAmount);
                    if (termMonths < next.minTermMonths || termMonths > next.maxTermMonths) setTermMonths(next.minTermMonths);
                  }
                }}
                fullWidth
              >
                {activeProducts.map((item) => (
                  <MenuItem key={item.code} value={item.code}>
                    {item.name} ({percent(item.baseRate)}, до {rub(item.maxAmount)})
                  </MenuItem>
                ))}
              </TextField>

              <TextField
                select
                label="Цель финансирования"
                value={purpose}
                onChange={(event) => setPurpose(event.target.value as FinancingPurpose)}
                fullWidth
              >
                {state.data.purposes
                  .filter((p) => !selectedProduct || selectedProduct.purposes.includes(p.code))
                  .map((item) => (
                    <MenuItem key={item.code} value={item.code}>{item.title}</MenuItem>
                  ))}
              </TextField>

              <Grid container spacing={2}>
                <Grid item xs={12} sm={6}>
                  <MoneyField
                    label="Сумма"
                    fullWidth
                    required
                    value={amount}
                    onChange={setAmount}
                    error={amountMissing || amountOutside}
                    helperText={
                      amountMissing
                        ? 'Укажите сумму кредита больше нуля'
                        : amountOutside && selectedProduct
                          ? `Сумма должна быть от ${rub(selectedProduct.minAmount)} до ${rub(selectedProduct.maxAmount)}`
                          : selectedProduct
                            ? `от ${rub(selectedProduct.minAmount)} до ${rub(selectedProduct.maxAmount)}`
                            : ' '
                    }
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    label="Срок, месяцев"
                    fullWidth
                    required
                    inputProps={{ inputMode: 'numeric' }}
                    value={termMonths || ''}
                    onChange={(event) => setTermMonths(Number(event.target.value.replace(/\D/g, '').slice(0, 3)) || 0)}
                    error={termMissing || termOutside}
                    helperText={
                      termMissing
                        ? 'Укажите срок в месяцах'
                        : termOutside && selectedProduct
                          ? `Срок должен быть от ${selectedProduct.minTermMonths} до ${selectedProduct.maxTermMonths} мес.`
                          : selectedProduct
                            ? `от ${selectedProduct.minTermMonths} до ${selectedProduct.maxTermMonths}`
                            : ' '
                    }
                  />
                </Grid>
              </Grid>

              <TextField
                label="Комментарий к цели"
                fullWidth
                multiline
                minRows={2}
                inputProps={{ maxLength: 1000 }}
                value={purposeComment}
                onChange={(event) => setPurposeComment(event.target.value)}
                onBlur={() => setPurposeComment((value) => value.trim())}
                helperText="Необязательно, но помогает банку быстрее принять решение"
              />

              <Grid container spacing={2}>
                <Grid item xs={12} sm={6}>
                  <MoneyField
                    label="Выручка за 12 месяцев"
                    fullWidth
                    required
                    value={declaredRevenue12m}
                    onChange={setDeclaredRevenue12m}
                    onBlur={() => setRevenueVisited(true)}
                    error={revenueVisited && revenueMissing}
                    helperText={
                      revenueVisited && revenueMissing
                        ? 'Укажите годовую выручку больше нуля (по данным отчётности)'
                        : 'Как в налоговой отчётности — банк сверит её с данными ФНС'
                    }
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <MoneyField
                    label="Платежи по кредитам, в месяц"
                    fullWidth
                    value={declaredMonthlyDebtPayments}
                    onChange={setDeclaredMonthlyDebtPayments}
                    helperText="0, если кредитов нет"
                  />
                </Grid>
              </Grid>

              <Button type="submit" variant="contained" size="large" disabled={submitting || !selectedProduct}>
                {submitting ? 'Создаём черновик…' : 'Создать черновик заявки'}
              </Button>
            </Stack>
          </Paper>
        </Grid>

        <Grid item xs={12} md={5}>
          <Paper data-tour="agent-client-applications" variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
            <Typography variant="h6" component="h2" sx={{ mb: 1.5 }}>Заявки компании</Typography>
            {companyApps.length === 0 ? (
              <EmptyState title="Заявок пока нет" description="Создайте черновик слева или оформите предодобренное предложение." />
            ) : (
              <Stack spacing={1.5}>
                {companyApps.map((app) => {
                  const tone = statusTone(app.status);
                  return (
                    <Card key={app.id} variant="outlined">
                      <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                        <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
                          <Typography fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>
                            {app.number ? `№ ${app.number}` : 'Черновик'} · {app.productName}
                          </Typography>
                          <Chip size="small" label={statusTitle(app.status)} color={tone === 'default' ? 'default' : tone} />
                        </Stack>
                        <Typography variant="body2" sx={{ mt: 0.5 }}>
                          {rub(app.amount)} · {months(app.termMonths)}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
                          {agentNextAction(app.status).label} · {formatDateTime(app.statusChangedAt)}
                        </Typography>
                        <Button
                          component={RouterLink}
                          to={`/agent/applications/${app.id}`}
                          size="small"
                          endIcon={<ChevronRightRoundedIcon />}
                          sx={{ mt: 1, ml: -0.5 }}
                        >
                          Открыть заявку
                        </Button>
                      </CardContent>
                    </Card>
                  );
                })}
              </Stack>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Stack>
  );
}
