import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  FormControlLabel,
  Grid,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
  useMediaQuery,
  useTheme
} from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import AutoAwesomeRoundedIcon from '@mui/icons-material/AutoAwesomeRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { useCallback, useRef, useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { agentApi } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { TableScrollRegion } from '../../components/TableScrollRegion';
import { useApi } from '../../hooks/useApi';
import { agentNextAction, statusTitle, statusTone } from '../../shared/applicationText';
import { formatDate, formatDateTime, months, percent, rub } from '../../shared/format';
import { commissionStatusLabels, legalFormLabels } from '../../shared/labels';
import { ApplicationSummary, PreApprovedOffer } from '../../types';

function KpiCard({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Paper variant="outlined" sx={{ p: { xs: 1.5, md: 2.5 }, height: '100%' }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography variant="h4" component="p" fontWeight={700} sx={{ mt: 0.5, fontSize: { xs: '1.375rem', md: undefined }, overflowWrap: 'anywhere' }}>{value}</Typography>
      {hint && <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: { xs: 'none', sm: 'block' } }}>{hint}</Typography>}
    </Paper>
  );
}

function PortfolioApplicationCard({ application }: { application: ApplicationSummary }) {
  const tone = statusTone(application.status);
  const next = agentNextAction(application.status);
  const commission = application.agentCommissionStatus;
  return (
    <Paper component="li" variant="outlined" sx={{ p: 2 }}>
      <Stack spacing={1}>
        <Stack direction="row" alignItems="center" gap={1} flexWrap="wrap" useFlexGap>
          <Typography fontWeight={700}>{application.number ? `№ ${application.number}` : 'Черновик'}</Typography>
          <Chip size="small" color={tone === 'default' ? 'default' : tone} label={statusTitle(application.status)} />
          {!!application.unreadChatCount && <Chip size="small" color="info" label={`Чат: ${application.unreadChatCount}`} />}
        </Stack>
        <Typography variant="body2" fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>
          {application.companyName ?? `ИНН ${application.companyInn}`}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {application.productName} · {rub(application.amount)} · {months(application.termMonths)}
        </Typography>
        <Typography variant="body2" color={next.requiresAgent ? 'warning.dark' : 'text.secondary'} fontWeight={next.requiresAgent ? 600 : 400}>
          {next.label}
        </Typography>
        {commission && commission !== 'NONE' && application.agentCommissionAmount != null && (
          <Typography variant="caption" color="text.secondary">
            {rub(application.agentCommissionAmount)} · {commissionStatusLabels[commission]}
          </Typography>
        )}
        <Button component={RouterLink} to={`/agent/applications/${application.id}`} variant="outlined" size="small"
          endIcon={<ChevronRightRoundedIcon />} sx={{ alignSelf: 'flex-start' }}>
          Открыть заявку
        </Button>
      </Stack>
    </Paper>
  );
}

export function AgentPortfolioPage() {
  const { session } = useAuth();
  const navigate = useNavigate();
  const narrow = useMediaQuery(useTheme().breakpoints.down('md'));
  const applicationsRef = useRef<HTMLDivElement>(null);
  const [onlyActionRequired, setOnlyActionRequired] = useState(false);
  const [applyingOfferId, setApplyingOfferId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const loadAll = useCallback(async () => {
    const [portfolio, clients, preApprovedOffers, applications] = await Promise.all([
      agentApi.portfolio(),
      agentApi.clients(),
      agentApi.preApprovedOffers(),
      agentApi.applications()
    ]);
    return { portfolio, clients, preApprovedOffers, applications };
  }, []);

  const state = useApi(loadAll);

  if (state.loading && !state.data) return <LoadingBlock />;
  if (!state.data) return <ErrorState message={state.error ?? 'Не удалось загрузить портфель агента'} onRetry={state.reload} />;

  const { portfolio, clients, preApprovedOffers, applications } = state.data;
  const offersByCompany = new Map<string, PreApprovedOffer[]>();
  for (const offer of preApprovedOffers) {
    if (offer.status !== 'ACTIVE') continue;
    const list = offersByCompany.get(offer.companyId) ?? [];
    list.push(offer);
    offersByCompany.set(offer.companyId, list);
  }

  const activeWorkCount = portfolio.draftCount + portfolio.pendingConfirmationCount + portfolio.inProgressCount;
  const actionRequired = applications.filter((item) => agentNextAction(item.status).requiresAgent);
  const visibleApps = onlyActionRequired ? actionRequired : applications;

  const handleApplyOffer = async (offerId: string) => {
    setApplyingOfferId(offerId);
    setActionError(null);
    try {
      const created = await agentApi.applyPreApprovedOffer(offerId);
      navigate(`/agent/applications/${created.id}`);
    } catch (nextError) {
      setActionError(nextError instanceof Error ? nextError.message : 'Не удалось создать заявку по предодобрению');
      setApplyingOfferId(null);
    }
  };

  return (
    <Stack spacing={4}>
      <PageHeader
        title="Портфель агента"
        subtitle={session ? `${session.user.fullName} · управление заявками закреплённых компаний` : undefined}
        actions={narrow && actionRequired.length > 0 && (
          <Button variant="contained" size="small" onClick={() => {
            setOnlyActionRequired(true);
            applicationsRef.current?.focus();
          }}>Требуют моего действия · {actionRequired.length}</Button>
        )}
      />

      {actionError && <Alert severity="error" onClose={() => setActionError(null)}>{actionError}</Alert>}

      <Grid container spacing={2} data-tour="agent-kpi">
        <Grid item xs={6} md={3}>
          <KpiCard label="Активных клиентов" value={String(portfolio.assignedCompaniesCount)} hint="Компаний с действующим мандатом" />
        </Grid>
        <Grid item xs={6} md={3}>
          <KpiCard
            label="Заявок в работе"
            value={String(activeWorkCount)}
            hint={`Черновиков: ${portfolio.draftCount} · у клиента: ${portfolio.pendingConfirmationCount} · в банке: ${portfolio.inProgressCount}`}
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <KpiCard label="Ожидаемая комиссия" value={rub(portfolio.expectedCommissionTotal)} hint="По активным заявкам до выдачи" />
        </Grid>
        <Grid item xs={6} md={3}>
          <KpiCard label="Начисленная комиссия" value={rub(portfolio.accruedCommissionTotal)} hint={`Выдано кредитов: ${portfolio.disbursedCount}`} />
        </Grid>
      </Grid>

      <Box data-tour="agent-clients">
        <Typography variant="h6" component="h2" sx={{ mb: 2 }}>Закреплённые клиенты</Typography>
        {clients.length === 0 ? (
          <EmptyState
            title="Нет закреплённых компаний"
            description="Когда банк закрепит за вами клиентов, они появятся в этом списке."
          />
        ) : (
          <Grid container spacing={2}>
            {clients.map((client) => {
              const clientOffers = offersByCompany.get(client.companyId) ?? [];
              const primaryOffer = clientOffers[0];
              return (
                <Grid item xs={12} md={6} key={client.companyId}>
                  <Card variant="outlined" sx={{ height: '100%' }}>
                    <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, height: '100%' }}>
                      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1} flexWrap="wrap" useFlexGap>
                        <Box sx={{ minWidth: 0 }}>
                          <Typography variant="subtitle1" fontWeight={700} sx={{ overflowWrap: 'anywhere' }}>
                            {client.name ?? `ИНН ${client.inn}`}
                          </Typography>
                          <Typography variant="body2" color="text.secondary">
                            {legalFormLabels[client.legalForm]} · ИНН {client.inn}
                          </Typography>
                        </Box>
                        {primaryOffer && (
                          <Chip
                            size="small"
                            color="success"
                            icon={<AutoAwesomeRoundedIcon />}
                            label={`Предодобрено до ${rub(primaryOffer.maxAmount)}`}
                          />
                        )}
                      </Stack>

                      {primaryOffer && (
                        <Typography variant="body2" color="text.secondary">
                          {primaryOffer.productName} · ставка {percent(primaryOffer.rate)} · действует до {formatDate(primaryOffer.validUntil)}
                        </Typography>
                      )}

                      <Stack direction="row" flexWrap="wrap" gap={1} sx={{ mt: 'auto', pt: 1 }}>
                        <Button
                          component={RouterLink}
                          to={`/agent/clients/${client.companyId}`}
                          variant="outlined"
                          size="small"
                          startIcon={<AddRoundedIcon />}
                        >
                          Оформить заявку
                        </Button>
                        {primaryOffer && (
                          <Button
                            variant="contained"
                            size="small"
                            disabled={applyingOfferId === primaryOffer.id}
                            onClick={() => void handleApplyOffer(primaryOffer.id)}
                          >
                            {applyingOfferId === primaryOffer.id ? 'Создаём…' : 'По предодобрению'}
                          </Button>
                        )}
                      </Stack>
                    </CardContent>
                  </Card>
                </Grid>
              );
            })}
          </Grid>
        )}
      </Box>

      <Box data-tour="agent-applications" ref={applicationsRef} tabIndex={-1} sx={{ scrollMarginTop: 88 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 2 }}>
          <Typography variant="h6" component="h2">Заявки портфеля</Typography>
          <FormControlLabel
            control={<Switch checked={onlyActionRequired} onChange={(event) => setOnlyActionRequired(event.target.checked)} />}
            label="Требуют моего действия"
          />
        </Stack>

        {visibleApps.length === 0 ? (
          <EmptyState
            title={onlyActionRequired ? 'Нет заявок, требующих вашего действия' : 'Заявок пока нет'}
            description={onlyActionRequired ? 'Отключите фильтр, чтобы увидеть все заявки портфеля.' : 'Выберите клиента выше, чтобы подготовить первую заявку.'}
          />
        ) : narrow ? (
          <Stack component="ul" aria-label="Заявки портфеля агента" spacing={1} sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {visibleApps.map((application) => <PortfolioApplicationCard key={application.id} application={application} />)}
          </Stack>
        ) : (
          <Paper variant="outlined" sx={{ p: { xs: 1, sm: 0 }, overflow: 'hidden' }}>
            <TableScrollRegion label="Заявки портфеля агента">
              <TableContainer>
                <Table size="small" aria-label="Заявки портфеля агента">
              <TableHead>
                <TableRow>
                  <TableCell>Заявка</TableCell>
                  <TableCell>Компания</TableCell>
                  <TableCell>Сумма и срок</TableCell>
                  <TableCell>Статус</TableCell>
                  <TableCell>Следующий шаг</TableCell>
                  <TableCell align="right">Комиссия</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {visibleApps.map((app) => {
                  const tone = statusTone(app.status);
                  const nextStep = agentNextAction(app.status);
                  return (
                    <TableRow key={app.id} hover>
                      <TableCell>
                        <Typography fontWeight={600} component={RouterLink} to={`/agent/applications/${app.id}`} sx={{ color: 'inherit', textDecoration: 'none' }}>
                          {app.number ? `№ ${app.number}` : 'Черновик'}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" display="block">
                          {app.productName} · {formatDateTime(app.statusChangedAt)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" fontWeight={500}>{app.companyName ?? `ИНН ${app.companyInn}`}</Typography>
                        <Typography variant="caption" color="text.secondary">ИНН {app.companyInn}</Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" fontWeight={600}>{rub(app.amount)}</Typography>
                        <Typography variant="caption" color="text.secondary">{months(app.termMonths)}</Typography>
                      </TableCell>
                      <TableCell>
                        <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap">
                          <Chip size="small" label={statusTitle(app.status)} color={tone === 'default' ? 'default' : tone} />
                          {(app.unreadChatCount ?? 0) > 0 && <Chip size="small" color="info" label={`Чат: ${app.unreadChatCount}`} />}
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" color={nextStep.requiresAgent ? 'warning.dark' : 'text.secondary'} fontWeight={nextStep.requiresAgent ? 600 : 400}>
                          {nextStep.label}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        {app.agentCommissionAmount != null && app.agentCommissionStatus && app.agentCommissionStatus !== 'NONE' ? (
                          <>
                            <Typography variant="body2" fontWeight={600}>{rub(app.agentCommissionAmount)}</Typography>
                            <Typography variant="caption" color="text.secondary">{commissionStatusLabels[app.agentCommissionStatus]}</Typography>
                          </>
                        ) : (
                          <Typography variant="body2" color="text.secondary">—</Typography>
                        )}
                      </TableCell>
                      <TableCell align="right">
                        <Button component={RouterLink} to={`/agent/applications/${app.id}`} size="small" endIcon={<ChevronRightRoundedIcon />}>
                          Открыть
                        </Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
                </Table>
              </TableContainer>
            </TableScrollRegion>
          </Paper>
        )}
      </Box>
    </Stack>
  );
}
