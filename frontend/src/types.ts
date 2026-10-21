export type Role = 'CLIENT' | 'MANAGER' | 'UNDERWRITER' | 'RISK_ADMIN';

export type LegalForm = 'LEGAL_ENTITY' | 'ENTREPRENEUR';

export interface User {
  id: string;
  fullName: string;
  email: string;
  role: Role;
  status: 'ACTIVE' | 'BLOCKED';
  companyId: string | null;
  canSign: boolean;
}

export interface UserSession {
  token: string;
  user: User;
}

export interface CompanyMember {
  id: string;
  fullName: string;
  canSign: boolean;
}

export interface Company {
  id: string;
  inn: string;
  name: string | null;
  legalForm: LegalForm;
  members: CompanyMember[];
}

export interface SignUpInput {
  fullName: string;
  email: string;
  password: string;
  inn: string;
  personalDataConsent: boolean;
}
