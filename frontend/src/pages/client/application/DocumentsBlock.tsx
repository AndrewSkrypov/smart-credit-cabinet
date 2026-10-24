import { Alert, Box, Button, IconButton, List, ListItem, ListItemText, Paper, Stack, Typography } from '@mui/material';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import RadioButtonUncheckedRoundedIcon from '@mui/icons-material/RadioButtonUncheckedRounded';
import { useState } from 'react';
import { applicationsApi } from '../../../api/client';
import { fileSize, formatDateTime } from '../../../shared/format';
import { Application } from '../../../types';
import { UploadButton } from './UploadButton';
import { SensitiveDataNotice } from '../../../components/SensitiveDataNotice';

/**
 * In a draft: the documents the application needs, with uploads. Once sent: what was uploaded
 * (a request of the bank lists what it still needs in its own block, with its own uploads).
 */
export function DocumentsBlock({ application, onChanged }: { application: Application; onChanged: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const draft = application.status === 'DRAFT';
  const canUpload = draft && application.canUploadDocuments;
  const canDelete = draft && application.editable;
  if (!draft && application.documents.length === 0) return null;

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6" component="h2" gutterBottom>Документы</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      {canUpload && <SensitiveDataNotice />}
      {!draft ? null : application.requiredDocuments.length > 0 ? (
        <Stack spacing={1.5} sx={{ mb: 2 }}>
          {application.requiredDocuments.map((required) => (
            <Stack key={required.type} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }} justifyContent="space-between">
              <Stack direction="row" spacing={1.5} alignItems="flex-start">
                {required.uploaded ? <CheckCircleRoundedIcon color="success" /> : <RadioButtonUncheckedRoundedIcon color="disabled" />}
                <Box>
                  <Typography fontWeight={600}>{required.title}</Typography>
                  <Typography variant="body2" color="text.secondary">{required.reason}</Typography>
                </Box>
              </Stack>
              {canUpload && !required.uploaded && (
                <UploadButton applicationId={application.id} documentType={required.type} onUploaded={onChanged} onError={setError} />
              )}
            </Stack>
          ))}
        </Stack>
      ) : (
        <Typography color="text.secondary" sx={{ mb: 2 }}>Для этой заявки обязательных документов нет. Банк запросит их, если понадобится.</Typography>
      )}
      {application.documents.length > 0 && (
        <>
          <Typography variant="subtitle2" color="text.secondary">Загружено</Typography>
          <List dense disablePadding>
            {application.documents.map((document) => (
              <ListItem
                key={document.id}
                disableGutters
                secondaryAction={canDelete && (
                  <IconButton
                    edge="end"
                    aria-label={`Удалить ${document.fileName}`}
                    onClick={() => applicationsApi.deleteDocument(application.id, document.id).then(onChanged).catch((e: Error) => setError(e.message))}
                  >
                    <DeleteOutlineRoundedIcon />
                  </IconButton>
                )}
              >
                <ListItemText
                  primary={
                    <Button variant="text" sx={{ p: 0, minHeight: 0, textAlign: 'left' }}
                      onClick={() => applicationsApi.downloadDocument(application.id, document.id, document.fileName).catch((e: Error) => setError(e.message))}>
                      {document.fileName}
                    </Button>
                  }
                  secondary={`${document.title} · ${fileSize(document.sizeBytes)} · ${formatDateTime(document.uploadedAt)}`}
                />
              </ListItem>
            ))}
          </List>
        </>
      )}
      {canUpload && (
        <Box sx={{ mt: 1 }}>
          <UploadButton applicationId={application.id} documentType="OTHER" onUploaded={onChanged} onError={setError} variant="text">
            Добавить другой документ
          </UploadButton>
        </Box>
      )}
      {canUpload && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>PDF, JPG, PNG, Word или Excel, до 20 МБ.</Typography>}
    </Paper>
  );
}
