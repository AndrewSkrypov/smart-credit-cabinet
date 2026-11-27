import { List, ListItem, ListItemText, Paper, Typography } from '@mui/material';
import { agentApi, applicationsApi } from '../../../api/client';
import { ErrorState } from '../../../components/ErrorState';
import { useApi } from '../../../hooks/useApi';
import { eventText, historyForClient } from '../../../shared/applicationText';
import { formatDateTime } from '../../../shared/format';
import { actorTypeLabels } from '../../../shared/labels';

/** The application's journal in the client's words, newest first. Reloads when the status changes. */
export function HistoryBlock({ applicationId, version, agentScope = false }: { applicationId: string; version: string; agentScope?: boolean }) {
  const { data, error, reload } = useApi(() => (agentScope ? agentApi : applicationsApi).events(applicationId), [applicationId, version, agentScope]);
  if (error && agentScope) return <ErrorState message="Не удалось загрузить разрешённую историю заявки" onRetry={reload} />;
  if (!data || data.length === 0) return null;
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6" component="h2">История</Typography>
      <List dense>
        {historyForClient(data).map(({ entry, repeats }) => (
          <ListItem key={entry.id} disableGutters>
            <ListItemText
              primary={repeats > 1 ? `${eventText(entry)} (${repeats} раза)` : eventText(entry)}
              secondary={[formatDateTime(entry.occurredAt), actorTypeLabels[entry.actorType], entry.comment].filter(Boolean).join(' · ')}
            />
          </ListItem>
        ))}
      </List>
    </Paper>
  );
}
