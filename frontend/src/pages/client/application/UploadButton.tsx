import { ButtonProps } from '@mui/material';
import { BusinessUploadButton } from '../../../components/BusinessUploadButton';
import { businessUploadScope } from '../../../shared/businessUpload';
import { applicationsApi } from '../../../api/client';
import { DocumentType } from '../../../types';

interface UploadButtonProps extends Omit<ButtonProps, 'onError'> {
  applicationId: string;
  documentType: DocumentType;
  privateToBank?: boolean;
  onUploaded: () => void;
  onError: (message: string) => void;
}

export function UploadButton({ applicationId, documentType, privateToBank, onUploaded, onError, children, ...props }: UploadButtonProps) {
  return <BusinessUploadButton
    scope={() => businessUploadScope('client', applicationId, documentType, String(!!privateToBank))}
    accept={privateToBank ? '.pdf,application/pdf' : undefined}
    upload={(file, key) => applicationsApi.uploadDocument(applicationId, documentType, file, privateToBank, key)}
    onUploaded={onUploaded} onError={onError} {...props}>{children}</BusinessUploadButton>;
}
