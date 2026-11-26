import { Button, ButtonProps } from '@mui/material';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import { useRef, useState } from 'react';
import { applicationsApi } from '../../../api/client';
import { DocumentType } from '../../../types';

interface UploadButtonProps extends Omit<ButtonProps, 'onError'> {
  applicationId: string;
  documentType: DocumentType;
  privateToBank?: boolean;
  onUploaded: () => void;
  onError: (message: string) => void;
}

const ACCEPT = '.pdf,.jpg,.jpeg,.png,.doc,.docx,.xls,.xlsx';

export function UploadButton({ applicationId, documentType, privateToBank, onUploaded, onError, children, ...props }: UploadButtonProps) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  return (
    <>
      <Button variant="outlined" startIcon={<UploadFileRoundedIcon />} disabled={busy} onClick={() => input.current?.click()} {...props}>
        {busy ? 'Загружаем…' : children ?? 'Загрузить'}
      </Button>
      <input
        ref={input}
        type="file"
        accept={privateToBank ? '.pdf,application/pdf' : ACCEPT}
        hidden
        onChange={async (event) => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (!file) return;
          setBusy(true);
          try {
            await applicationsApi.uploadDocument(applicationId, documentType, file, privateToBank);
            onUploaded();
          } catch (error) {
            onError(error instanceof Error ? error.message : 'Не удалось загрузить файл');
          } finally {
            setBusy(false);
          }
        }}
      />
    </>
  );
}
