import { Box, Chip, Paper, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { applicationsApi } from '../../api/client';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { statusTitle, statusTone } from '../../shared/applicationText';
import { hoursSince, waitingFor } from '../../shared/duration';
import { months, rub } from '../../shared/format';
import { ApplicationStatus, ApplicationSummary } from '../../types';

/** Target time of a manual review (docs/process-model.md, section 8): longer waits are highlighted. */
const REVIEW_SLA_HOURS = 8;

const groups: Array<{ key: string; title: string; statuses: ApplicationStatus[]; oldestFirst: boolean }> = [
  { key: 'review', title: 'Ждут решения', statuses: ['MANUAL_REVIEW'], oldestFirst: true },
  { key: 'client', title: 'Ждут клиента', statuses: ['INFO_REQUESTED', 'OFFER_ISSUED', 'SIGNING'], oldestFirst: true },
  { key: 'system', title: 'В обработке', statuses: ['SUBMITTED', 'ENRICHMENT', 'SCORING', 'DISBURSING'], oldestFirst: true },
  { key: 'done', title: 'Завершённые', statuses: ['DISBURSED', 'REJECTED', 'WITHDRAWN', 'CLOSED'], oldestFirst: false }
];

const REFRESH_MS = 10000;

export function BankQueuePage() {
  const navigate = useNavigate();
  const { data, loading, error, reload } = useApi(applicationsApi.list);
  const [tab, setTab] = useState('review');

  useEffect(() => {
    const timer = window.setInterval(() => void reload(), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [reload]);

  if (loading && !data) return <LoadingBlock />;
  if (error && !data) return <ErrorState message={error} onRetry={reload} />;
  const all = data ?? [];
  const group = groups.find((item) => item.key === tab)!;
  const rows = all
    .filter((item) => group.statuses.includes(item.status))
    .sort((a, b) => (new Date(a.statusChangedAt).getTime() - new Date(b.statusChangedAt).getTime()) * (group.oldestFirst ? 1 : -1));

  return (
    <>
      <PageHeader title="Заявки" subtitle="Очередь заявок малого и микробизнеса. Сверху — те, что ждут дольше всех." />
      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Tabs value={tab} onChange={(_, value: string) => setTab(value)} variant="scrollable" allowScrollButtonsMobile sx={{ px: { xs: 1, md: 2 }, borderBottom: 1, borderColor: 'divider' }}>
          {groups.map((item) => {
            const count = all.filter((application) => item.statuses.includes(application.status)).length;
            return <Tab key={item.key} value={item.key} label={`${item.title} · ${count}`} sx={{ minHeight: 56 }} />;
          })}
        </Tabs>
        {rows.length === 0 ? (
          <Box sx={{ p: 3 }}>
            <EmptyState title="Здесь пусто" description={tab === 'review' ? 'Заявок на ручном рассмотрении нет — всё решено автоматически.' : 'Заявок в этом состоянии нет.'} />
          </Box>
        ) : (
          <Box sx={{ overflowX: 'auto' }}>
            <Table aria-label={group.title}>
              <TableHead>
                <TableRow>
                  <TableCell>Заявка</TableCell>
                  <TableCell>Компания</TableCell>
                  <TableCell align="right">Сумма и срок</TableCell>
                  <TableCell>Статус</TableCell>
                  <TableCell align="right">В статусе</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((item) => <QueueRow key={item.id} item={item} onOpen={() => navigate(`/bank/applications/${item.id}`)} />)}
              </TableBody>
            </Table>
          </Box>
        )}
      </Paper>
    </>
  );
}

function QueueRow({ item, onOpen }: { item: ApplicationSummary; onOpen: () => void }) {
  const tone = statusTone(item.status);
  const overdue = item.status === 'MANUAL_REVIEW' && hoursSince(item.statusChangedAt) > REVIEW_SLA_HOURS;
  return (
    <TableRow hover onClick={onOpen} onKeyDown={(event) => event.key === 'Enter' && onOpen()} tabIndex={0} sx={{ cursor: 'pointer' }}>
      <TableCell>
        <Typography fontWeight={700}>№ {item.number}</Typography>
        <Typography variant="body2" color="text.secondary">{item.productName}</Typography>
      </TableCell>
      <TableCell>
        <Typography>{item.companyName ?? '—'}</Typography>
        <Typography variant="body2" color="text.secondary">ИНН {item.companyInn}</Typography>
      </TableCell>
      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
        <Typography>{rub(item.amount)}</Typography>
        <Typography variant="body2" color="text.secondary">{months(item.termMonths)}</Typography>
      </TableCell>
      <TableCell><Chip size="small" label={statusTitle(item.status)} color={tone === 'default' ? 'default' : tone} /></TableCell>
      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
        <Typography color={overdue ? 'error' : 'text.primary'} fontWeight={overdue ? 700 : 400}>{waitingFor(item.statusChangedAt)}</Typography>
        {overdue && <Typography variant="caption" color="error">дольше {REVIEW_SLA_HOURS} ч</Typography>}
      </TableCell>
    </TableRow>
  );
}
