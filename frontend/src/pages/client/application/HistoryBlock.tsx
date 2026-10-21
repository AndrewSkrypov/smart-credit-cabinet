import { List, ListItem, ListItemText, Paper, Typography } from '@mui/material';
import { applicationsApi } from '../../../api/client';
import { useApi } from '../../../hooks/useApi';
import { eventText, historyForClient } from '../../../shared/applicationText';
import { formatDateTime } from '../../../shared/format';

/** The application's journal in the client's words, newest first. Reloads when the status changes. */
export function HistoryBlock({ applicationId, version }: { applicationId: string; version: string }) {
  const { data } = useApi(() => applicationsApi.events(applicationId), [applicationId, version]);
  if (!data || data.length === 0) return null;
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6" component="h2">История</Typography>
      <List dense>
        {historyForClient(data).map(({ entry, repeats }) => (
          <ListItem key={entry.id} disableGutters>
            <ListItemText
              primary={repeats > 1 ? `${eventText(entry)} (${repeats} раза)` : eventText(entry)}
              secondary={[formatDateTime(entry.occurredAt), entry.actorType === 'UNDERWRITER' || entry.actorType === 'MANAGER' ? entry.comment : null].filter(Boolean).join(' · ')}
            />
          </ListItem>
        ))}
      </List>
    </Paper>
  );
}
