import { Alert, Box, Button, Card, CardActionArea, Chip, Grid, IconButton, Paper, Stack, Typography } from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { Link as RouterLink, useNavigate, useLocation } from 'react-router-dom';
import { companyApi } from '../api/client';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingBlock } from '../components/LoadingBlock';
import { PageHeader } from '../components/PageHeader';
import { Illustration } from '../components/Illustration';
import { ApplicationListRegion, ApplicationPageFilters, ApplicationPageNavigation, ApplicationPageStatus } from '../components/ApplicationPageControls';
import { useApplicationPage, useApplicationPageParams } from '../hooks/useApplicationPage';
import { useApi } from '../hooks/useApi';
import { statusTitle, statusTone } from '../shared/applicationText';
import { formatDateTime, months, rub } from '../shared/format';
import { legalFormLabels } from '../shared/labels';
import { tokens } from '../theme/theme';
import { ApplicationSummary } from '../types';

function ApplicationRow({ item, onOpen }: { item: ApplicationSummary; onOpen: () => void }) {
  const location = useLocation();
  const tone = statusTone(item.status);
  return (
    <Card>
      <CardActionArea component={RouterLink} onClick={onOpen} to={`/client/applications/${item.id}`} state={{ listUrl: location.pathname + location.search }} sx={{ p: { xs: 2, md: 3 } }}>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="body2" color="text.secondary">№ {item.number} · обновлена {formatDateTime(item.statusChangedAt)}</Typography>
            <Typography fontWeight={700} sx={{ mt: 0.5 }}>{item.productName}</Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1, sm: 2 }} alignItems={{ sm: 'center' }} sx={{ mt: 1 }}>
              <Typography variant="subtitle1" component="p" fontWeight={700}>{rub(item.amount)} <Typography component="span" color="text.secondary">· {months(item.termMonths)}</Typography></Typography>
              <Chip label={statusTitle(item.status)} color={tone === 'default' ? 'default' : tone} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }} />
              {(item.unreadChatCount ?? 0) > 0 && <Chip size="small" color="info" label={`Сообщений: ${item.unreadChatCount}`} />}
            </Stack>
          </Box>
          <ChevronRightRoundedIcon aria-hidden sx={{ color: 'text.secondary' }} />
        </Stack>
      </CardActionArea>
    </Card>
  );
}

export function ClientHomePage() {
  const navigate = useNavigate();
  const company = useApi(companyApi.mine);
  const location = useLocation();
  const pageParams = useApplicationPageParams();
  const finished = useApplicationPage('done', 10, ['done'], [10], 5000, { params: pageParams, fixedGroup: 'done', pageParam: 'donePage', scrollTarget: 'client-done', restoreScroll: false });
  const applications = useApplicationPage('active', 10, ['active', 'action'], [10], 5000, { params: pageParams, pageParam: 'activePage', scrollTarget: 'client-active', ready: !!company.data && (!!finished.data || !!finished.error) });
  if (company.loading && !company.data) return <LoadingBlock />;
  if (!company.data) return <ErrorState message={company.error ?? 'Компания не найдена'} onRetry={company.reload} />;
  const page = applications.data ?? applications.previousData;
  const waitingForClient = page?.actionRequiredPreview ?? [];
  const counts = applications.counts ?? finished.counts;
  const rememberPosition = () => { applications.rememberPosition(); finished.rememberPosition(); };

  return (
    <>
      <PageHeader
        title={company.data.name ?? `ИНН ${company.data.inn}`}
        subtitle={`${legalFormLabels[company.data.legalForm]} · ИНН ${company.data.inn}`}
        actions={<Button component={RouterLink} to="/client/new" variant="contained" size="large" startIcon={<AddRoundedIcon />}>Подобрать финансирование</Button>}
      />
      {company.error && (
        <Alert severity="warning" sx={{ mb: 3 }} action={<Button onClick={() => {
          if (company.error) void company.reload();
          if (applications.error) void applications.reload();
        }}>Повторить</Button>}>
          Не удалось обновить данные: {company.error ?? applications.error}. Показаны последние полученные сведения.
        </Alert>
      )}
      <Grid container spacing={3}>
        <Grid item xs={12} md={8}>
          <Stack spacing={3}>
            {!!page?.actionRequiredCount && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
                <Typography variant="h6" component="h2">Нужно ваше действие · {page?.actionRequiredCount}</Typography>
                <Stack spacing={0.5} sx={{ mt: 1 }}>
                  {waitingForClient.map((item) => (
                    <Typography key={item.id} component={RouterLink} onClick={rememberPosition} to={`/client/applications/${item.id}`} state={{ listUrl: location.pathname + location.search }} sx={{ color: 'inherit' }}>
                      № {item.number} — {statusTitle(item.status).toLowerCase()}
                    </Typography>
                  ))}
                </Stack>
                {(page?.actionRequiredCount ?? 0) > waitingForClient.length && <Button onClick={() => applications.update({ group: 'action', q: '', page: 0 })}>Все {page?.actionRequiredCount}</Button>}
              </Paper>
            )}

            <Box>
              <ApplicationPageFilters state={applications} status={false} />
              <ApplicationPageStatus states={[applications, finished]} />
              <Stack spacing={3}>
                {([['В работе', applications, 'client-active', 'active'], ['Завершённые', finished, 'client-done', 'done']] as const).map(([title, state, id, countKey]) =>
                  (!counts || !!counts[countKey] || state.loading || !!state.error) && <Box key={id}>
                    <Typography variant="h6" component="h2" sx={{ mb: 1.5 }}>{title}</Typography>
                    {id === 'client-active' && applications.query.group === 'action' && <Button onClick={() => applications.update({ group: 'active', page: 0 })}>Все заявки в работе</Button>}
                    <ApplicationListRegion state={state} id={id}>
                      {state.error && !state.data && <ErrorState message={state.error} onRetry={state.reload} />}
                      {state.data && (state.data.items.length ? <Stack spacing={1.5}>{state.data.items.map(item => <ApplicationRow key={item.id} item={item} onOpen={rememberPosition} />)}</Stack> : null)}
                    </ApplicationListRegion>
                    <ApplicationPageNavigation state={state} />
                  </Box>)
                }
                {counts && !counts.active && !counts.done && !applications.loading && !finished.loading && !applications.error && !finished.error &&
                  <EmptyState title={applications.query.q ? 'По вашему поиску заявок нет' : 'Заявок пока нет'}
                    description="Расскажите, на что нужны деньги, — подберём продукт, посчитаем платёж и поможем подать заявку."
                    actionLabel="Подобрать финансирование" onAction={() => navigate('/client/new')} />}
              </Stack>
            </Box>
          </Stack>
        </Grid>

        <Grid item xs={12} md={4}>
          <Stack spacing={3} sx={{ position: { md: 'sticky' }, top: { md: 96 } }}>
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, position: 'relative' }}>
              <Typography variant="subtitle1" component="h2" fontWeight={700} sx={{ pr: 4 }}>Представители компании</Typography>
              <IconButton component={RouterLink} to="/client/employees" size="small" aria-label={`Все сотрудники · ${company.data.members.length}`}
                sx={{ position: 'absolute', top: { xs: 8, md: 24 }, right: { xs: 8, md: 16 } }}><ChevronRightRoundedIcon /></IconButton>
              <Stack spacing={1.5} sx={{ mt: 1.5 }}>
                {company.data.members.slice(0, 5).map((member) => (
                  <Stack key={member.id} direction="row" spacing={1.5} alignItems="center">
                    <Box aria-hidden sx={{ width: 40, height: 40, flexShrink: 0, borderRadius: '99px', bgcolor: tokens.secondaryFill, display: 'grid', placeItems: 'center', fontWeight: 700 }}>
                      {member.fullName.charAt(0)}
                    </Box>
                    <Box>
                      <Typography>{member.fullName}</Typography>
                      {member.canSign && <Typography variant="body2" color="text.secondary">право подписи</Typography>}
                    </Box>
                  </Stack>
                ))}
              </Stack>
            </Paper>
            <Paper sx={{ p: { xs: 2, md: 3 }, bgcolor: tokens.tilePurple, color: '#fff', position: 'relative', overflow: 'hidden' }}>
              <Illustration name="coin" size={96} sx={{ position: 'absolute', right: -8, top: -8, transform: 'rotate(-10deg)' }} />
              <Typography variant="subtitle1" component="h2" fontWeight={700} sx={{ maxWidth: '70%' }}>Нужны деньги на бизнес?</Typography>
              <Typography sx={{ mt: 1, color: 'rgba(255,255,255,0.85)', maxWidth: '80%' }}>Подберём продукт под задачу и сразу покажем платёж.</Typography>
              <Button component={RouterLink} to="/client/new" variant="contained" sx={{ mt: 2 }}>Подобрать</Button>
            </Paper>
          </Stack>
        </Grid>
      </Grid>
    </>
  );
}
