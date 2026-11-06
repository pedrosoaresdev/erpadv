// Modelos espelhando os DTOs da API (backend/src/main/java/br/com/erpadv/dto).

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface ApiProblem {
  status: number;
  code: string;
  detail: string;
  traceId?: string;
  fields?: Record<string, string>;
}

export type Permission =
  | 'DASHBOARD_VIEW' | 'FINANCE_DASHBOARD_VIEW'
  | 'CLIENTS_READ' | 'CLIENTS_WRITE' | 'CLIENTS_SENSITIVE_READ' | 'CLIENTS_INACTIVATE'
  | 'CASES_READ' | 'CASES_WRITE' | 'CASES_ARCHIVE'
  | 'DEADLINES_READ' | 'DEADLINES_WRITE' | 'CALENDAR_READ' | 'CALENDAR_WRITE'
  | 'FINANCE_READ' | 'FINANCE_WRITE' | 'CONTRACTS_READ' | 'CONTRACTS_WRITE'
  | 'DOCUMENTS_READ' | 'DOCUMENTS_WRITE' | 'DOCUMENTS_CONFIDENTIAL'
  | 'CRIMINAL_READ' | 'CRIMINAL_WRITE' | 'AI_USE' | 'MESSAGES_USE' | 'REPORTS_VIEW'
  | 'EMPLOYEES_READ' | 'EMPLOYEES_MANAGE' | 'AUDIT_READ' | 'ADMIN_PANEL' | 'SETTINGS_MANAGE'
  | 'PLATFORM_ADMIN' | 'PORTAL_ACCESS';

export type Role =
  | 'MASTER' | 'SOCIO_ADMIN' | 'ADVOGADO' | 'ADVOGADO_ASSOCIADO' | 'ESTAGIARIO'
  | 'ASSISTENTE_JURIDICO' | 'SECRETARIA' | 'FINANCEIRO' | 'ADMINISTRATIVO' | 'TI_SUPORTE' | 'CLIENTE';

export interface Me {
  uid: string;
  email: string;
  name: string | null;
  emailVerified: boolean;
  onboardingRequired: boolean;
  emailVerificationRequired: boolean;
  officeId: string | null;
  officeName: string | null;
  role: Role | null;
  roleLabel: string | null;
  clientId: string | null;
  permissions: Permission[];
}

// ------------------------------------------------------------------ clientes
export type ClientType = 'PF' | 'PJ';
export type ClientStatus = 'ACTIVE' | 'INACTIVE';
export type InteractionChannel = 'PRESENCIAL' | 'TELEFONE' | 'WHATSAPP' | 'EMAIL' | 'VIDEOCONFERENCIA' | 'OUTRO';

export interface Address {
  zipCode?: string | null;
  street?: string | null;
  number?: string | null;
  complement?: string | null;
  neighborhood?: string | null;
  city?: string | null;
  state?: string | null;
}

export interface ClientSummary {
  id: string;
  type: ClientType;
  name: string;
  documentMasked: string;
  phone: string | null;
  email: string | null;
  status: ClientStatus;
  defaulting: boolean;
  createdAt: string;
}

export interface Client {
  id: string;
  type: ClientType;
  name: string;
  tradeName: string | null;
  document: string;
  documentMasked: string;
  rg: string | null;
  sensitiveVisible: boolean;
  birthDate: string | null;
  phone: string | null;
  whatsapp: string | null;
  email: string | null;
  address: Address | null;
  profession: string | null;
  maritalStatus: string | null;
  notes: string | null;
  status: ClientStatus;
  defaulting: boolean;
  responsibleUid: string | null;
  inactivatedAt: string | null;
  inactivationReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ClientRequest {
  type: ClientType;
  name: string;
  tradeName?: string | null;
  document?: string | null;
  rg?: string | null;
  birthDate?: string | null;
  phone?: string | null;
  whatsapp?: string | null;
  email?: string | null;
  address?: Address | null;
  profession?: string | null;
  maritalStatus?: string | null;
  notes?: string | null;
  responsibleUid?: string | null;
}

export interface ClientInteraction {
  id: string;
  clientId: string;
  channel: InteractionChannel;
  subject: string;
  description: string | null;
  occurredAt: string;
  userUid: string;
  userName: string;
}

// ------------------------------------------------------------------ processos
export type LegalArea =
  | 'CIVEL' | 'TRABALHISTA' | 'CRIMINAL' | 'TRIBUTARIO' | 'FAMILIA' | 'PREVIDENCIARIO'
  | 'EMPRESARIAL' | 'CONSUMIDOR' | 'ADMINISTRATIVO' | 'AMBIENTAL' | 'OUTRO';
export type CaseStatus = 'EM_ANDAMENTO' | 'AGUARDANDO_JULGAMENTO' | 'EM_RECURSO' | 'SUSPENSO' | 'ENCERRADO' | 'ARQUIVADO';
export type MovementType =
  | 'MOVIMENTACAO' | 'DESPACHO' | 'DECISAO' | 'SENTENCA' | 'ACORDAO' | 'AUDIENCIA' | 'PETICAO' | 'NOTA_INTERNA' | 'SISTEMA';

export interface LegalCase {
  id: string;
  cnjNumber: string | null;
  clientId: string;
  clientName: string;
  opposingParty: string | null;
  court: string | null;
  courtDivision: string | null;
  district: string | null;
  state: string | null;
  area: LegalArea;
  areaLabel: string;
  proceduralClass: string | null;
  subject: string | null;
  responsibleUid: string;
  responsibleName: string;
  claimValueCents: number | null;
  distributionDate: string | null;
  status: CaseStatus;
  statusLabel: string;
  notes: string | null;
  confidential: boolean;
  restricted: boolean;
  archived: boolean;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CaseRequest {
  cnjNumber?: string | null;
  clientId: string;
  opposingParty?: string | null;
  court?: string | null;
  courtDivision?: string | null;
  district?: string | null;
  state?: string | null;
  area: LegalArea;
  proceduralClass?: string | null;
  subject?: string | null;
  responsibleUid: string;
  claimValueCents?: number | null;
  distributionDate?: string | null;
  status: CaseStatus;
  notes?: string | null;
  confidential: boolean;
}

export interface CaseMovement {
  id: string;
  caseId: string;
  type: MovementType;
  date: string;
  title: string;
  description: string | null;
  source: string;
  authorUid: string;
  authorName: string;
  createdAt: string;
}

// ------------------------------------------------------------------ equipe
export type UserStatus = 'INVITED' | 'ACTIVE' | 'BLOCKED';

export interface Employee {
  uid: string;
  email: string;
  displayName: string;
  role: Role;
  roleLabel: string;
  status: UserStatus;
  oabNumber: string | null;
  phone: string | null;
  position: string | null;
  permissionsGranted: Permission[];
  permissionsRevoked: Permission[];
  effectivePermissions: Permission[];
  invitedAt: string | null;
  activatedAt: string | null;
  lastLoginAt: string | null;
  blockedAt: string | null;
  blockedReason: string | null;
}

export interface EmployeeOption {
  uid: string;
  displayName: string;
  role: Role;
  oabNumber: string | null;
}

export interface RoleInfo {
  role: Role;
  label: string;
  staff: boolean;
  permissions: Permission[];
}

export interface PermissionInfo {
  code: Permission;
  module: string;
  description: string;
}

// ------------------------------------------------------------------ dashboard
export interface MonthPoint {
  month: string;
  value: number;
  cumulative: number | null;
}

export interface NamedCount {
  key: string;
  label: string;
  value: number;
}

export interface Dashboard {
  from: string;
  to: string;
  clients: { total: number; active: number; inactive: number; defaulting: number; newInPeriod: number };
  cases: { total: number; inProgress: number; closed: number; archived: number; newInPeriod: number };
  clientGrowth: MonthPoint[];
  newCasesByMonth: MonthPoint[];
  casesByArea: NamedCount[];
  casesByStatus: NamedCount[];
  productivity: NamedCount[];
  pendingModules: Record<string, string>;
}

// ------------------------------------------------------------------ auditoria
export interface AuditLog {
  id: string;
  seq: number;
  at: string;
  actorUid: string;
  actorEmail: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  summary: string;
  details: Record<string, string>;
  ip: string | null;
  hash: string;
  prevHash: string;
}

export interface AuditVerifyResult {
  valid: boolean;
  checked: number;
  brokenAtSeq: number | null;
  message: string;
}

export interface AppNotification {
  id: string;
  title: string;
  body: string;
  link: string | null;
  read: boolean;
  createdAt: number;
}
