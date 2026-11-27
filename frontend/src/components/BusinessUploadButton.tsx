import { Button, ButtonProps } from '@mui/material';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import { useRef, useState } from 'react';
import { BusinessUploadOperation, createBusinessUpload } from '../shared/businessUpload';

interface Props extends Omit<ButtonProps, 'onError'> {
  scope: string;
  accept?: string;
  upload: (file: File, key: string) => Promise<unknown>;
  onUploaded: () => void;
  onError: (message: string) => void;
}

export function BusinessUploadButton({ scope, accept = '.pdf,.jpg,.jpeg,.png,.doc,.docx,.xls,.xlsx', upload, onUploaded, onError, children, disabled, ...props }: Props) {
  const input = useRef<HTMLInputElement>(null);
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  // Retry retains this File and key; choosing a file explicitly starts a new operation.
  const [failed, setFailed] = useState<BusinessUploadOperation | null>(null);
  const retry = failed?.scope === scope ? failed : null;

  async function send(operation: BusinessUploadOperation) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try {
      await upload(operation.file, operation.key);
      setFailed(null);
      onUploaded();
    } catch (error) {
      setFailed(operation);
      onError(error instanceof Error ? error.message : 'Не удалось загрузить файл');
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return <>
    <Button variant="outlined" startIcon={<UploadFileRoundedIcon />} {...props} disabled={disabled || busy} onClick={() => input.current?.click()}>
      {busy ? 'Загружаем…' : children ?? 'Загрузить'}
    </Button>
    {retry && <Button disabled={disabled || busy} onClick={() => void send(retry)}>Повторить загрузку {retry.file.name}</Button>}
    <input ref={input} type="file" accept={accept} hidden onChange={(event) => {
      const file = event.target.files?.[0];
      event.target.value = '';
      if (file && !inFlight.current) {
        setFailed(null);
        void send(createBusinessUpload(file, scope));
      }
    }} />
  </>;
}
