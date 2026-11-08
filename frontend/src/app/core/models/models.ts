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
  | 'CLIENTS_READ' | 'CLIENTS_WRITE' | 'CLIENTS_SENSITIVE_READ' | 'CLIENTS_INACTIVATE' | 'CLIENTS_DELETE'
  | 'CASES_READ' | 'CASES_WRITE' | 'CASES_ARCHIVE' | 'CASES_DELETE'
  | 'DEADLINES_READ' | 'DEADLINES_WRITE' | 'CALENDAR_READ' | 'CALENDAR_WRITE'
  | 'FINANCE_READ' | 'FINANCE_WRITE' | 'CONTRACTS_READ' | 'CONTRACTS_WRITE'
  | 'DOCUMENTS_READ' | 'DOCUMENTS_WRITE' | 'DOCUMENTS_CONFIDENTIAL' | 'DOCUMENTS_DELETE'
  | 'CRIMINAL_READ' | 'CRIMINAL_WRITE' | 'AI_USE' | 'MESSAGES_USE' | 'BOARDS_USE' | 'CRM_USE' | 'REPORTS_VIEW'
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
  platformAdmin: boolean;
  customRoleName: string | null;
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
  nationality: string | null;
  stateRegistration: string | null;
  municipalRegistration: string | null;
  legalRepresentative: string | null;
  legalRepresentativeRole: string | null;
  contacts: ClientContact[] | null;
  source: string | null;
  sourceDetail: string | null;
  lastActivityAt: string | null;
}

export interface ClientContact {
  name: string;
  role: string | null;
  phone: string | null;
  email: string | null;
}

export interface DuplicateMatch {
  client: ClientSummary;
  reason: string;
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
  nationality?: string | null;
  stateRegistration?: string | null;
  municipalRegistration?: string | null;
  legalRepresentative?: string | null;
  legalRepresentativeRole?: string | null;
  contacts?: ClientContact[];
  source?: string | null;
  sourceDetail?: string | null;
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
  | 'MOVIMENTACAO' | 'DESPACHO' | 'DECISAO' | 'SENTENCA' | 'RECURSO' | 'TRANSITO_JULGADO' | 'SUSPENSAO' | 'ACORDAO'
  | 'AUDIENCIA' | 'PETICAO' | 'NOTA_INTERNA' | 'SISTEMA';

export type CasePhase = 'POSTULATORIA' | 'INSTRUTORIA' | 'DECISORIA' | 'RECURSAL' | 'CUMPRIMENTO' | 'EXECUCAO' | 'ENCERRAMENTO';

export interface LegalCase {
  integrationSyncedAt?: string | null;
  integrationStatus?: 'OK' | 'NAO_ENCONTRADO' | 'ERRO' | null;
  integrationError?: string | null;
  integrationSource?: string | null;
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
  activeParty: string | null;
  passiveParty: string | null;
  instance: string | null;
  judgingBody: string | null;
  secondarySubjects: string[] | null;
  lawyerOab: string | null;
  freeJustice: boolean;
  phase: CasePhase | null;
  phaseLabel: string | null;
  sentenceDate: string | null;
  finalJudgmentDate: string | null;
  responsibleHistory: { uid: string; name: string; from: string; to: string | null }[] | null;
  lastMovementAt: string | null;
}

export interface CaseRelated {
  deadlines: { id: string; description: string; dueDate: string; status: string; validated: boolean; responsibleName: string }[] | null;
  appointments: { id: string; title: string; type: string; typeLabel: string; start: string; end: string; location: string | null }[] | null;
  transactions: { id: string; direction: 'RECEITA' | 'DESPESA'; categoryLabel: string; description: string; amountCents: number; dueDate: string; status: string; paidDate: string | null }[] | null;
  contracts: { id: string; number: string; title: string; status: string; feeModel: string }[] | null;
  cards: { id: string; boardId: string; boardName: string; title: string; columnName: string; dueDate: string | null; completed: boolean }[] | null;
  documents: number;
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
  activeParty?: string | null;
  passiveParty?: string | null;
  instance?: string | null;
  judgingBody?: string | null;
  secondarySubjects?: string[];
  freeJustice?: boolean;
  phase?: CasePhase | null;
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
  important?: boolean;
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
  customRoleId: string | null;
  customRoleName: string | null;
  cpfMasked: string | null;
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
  deadlines: { dueToday: number; urgent: number; overdue: number; upcoming: number; completedInPeriod: number; unvalidated: number };
  hearings: { next30Days: number; byMonth: MonthPoint[] };
  contracts: { active: number; expiring30Days: number; drafts: number };
  pendingModules: Record<string, string>;
  lawyerUid: string | null;
  deadlineWindows: { next3Days: number; next7Days: number; next15Days: number };
  caseExtra: { suspended: number; awaitingMovement: number; staleDays: number };
  agendaNext7Days: number;
  deadlineTrend: { month: string; completed: number; missed: number }[];
  casesByCourt: NamedCount[];
  clientsBySource: NamedCount[];
  team: { uid: string; name: string; activeCases: number; deadlinesCompleted: number; deadlinesOverdue: number }[];
  previous: { from: string; to: string; newClients: number; newCases: number; deadlinesCompleted: number };
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
  type: string;
  title: string;
  body: string;
  link: string | null;
  read: boolean;
  createdAt: number;
}

// ------------------------------------------------------------------ prazos (Etapa 9)
export type DeadlineType =
  | 'CONTESTACAO' | 'REPLICA' | 'RECURSO' | 'CONTRARRAZOES' | 'EMBARGOS_DECLARACAO' | 'MANIFESTACAO'
  | 'CUMPRIMENTO' | 'RESPOSTA_ACUSACAO' | 'ALEGACOES_FINAIS' | 'PRAZO_ADMINISTRATIVO' | 'OUTRO';
export type CountingMode = 'UTEIS' | 'CORRIDOS' | 'MANUAL';
export type DeadlinePriority = 'BAIXA' | 'MEDIA' | 'ALTA' | 'CRITICA';
export type DeadlineStatus = 'PENDENTE' | 'CONCLUIDO' | 'CANCELADO';
export type DeadlineSituation = 'FUTURO' | 'URGENTE' | 'HOJE' | 'VENCIDO' | 'CONCLUIDO' | 'CANCELADO';
export type DeadlineView = 'PENDENTES' | 'HOJE' | 'URGENTES' | 'FUTUROS' | 'VENCIDOS' | 'CONCLUIDOS' | 'CANCELADOS' | 'TODOS';

export interface Deadline {
  id: string;
  caseId: string | null;
  caseNumber: string | null;
  clientId: string | null;
  clientName: string | null;
  description: string;
  type: DeadlineType;
  typeLabel: string;
  startDate: string;
  dueDate: string;
  days: number | null;
  countingMode: CountingMode;
  court: string | null;
  responsibleUid: string;
  responsibleName: string;
  priority: DeadlinePriority;
  status: DeadlineStatus;
  situation: DeadlineSituation;
  daysLeft: number;
  notes: string | null;
  validated: boolean;
  validatedByName: string | null;
  validatedAt: string | null;
  completedAt: string | null;
  completedByName: string | null;
  restricted: boolean;
  createdAt: string;
  origin: DeadlineOrigin | null;
  originLabel: string | null;
  availabilityDate: string | null;
  publicationDate: string | null;
  awarenessDate: string | null;
  receiptName: string | null;
  receiptUploadedAt: string | null;
}

export type DeadlineOrigin = 'PUBLICACAO_DJE' | 'INTIMACAO_ELETRONICA' | 'INTIMACAO_PESSOAL' | 'CITACAO' | 'AUDIENCIA'
  | 'DESPACHO' | 'CONTRATUAL' | 'OUTRO';

export interface DeadlineRequest {
  caseId?: string | null;
  clientId?: string | null;
  description: string;
  type: DeadlineType;
  startDate: string;
  dueDate?: string | null;
  days?: number | null;
  countingMode: CountingMode;
  court?: string | null;
  responsibleUid: string;
  priority: DeadlinePriority;
  notes?: string | null;
  origin?: DeadlineOrigin | null;
  availabilityDate?: string | null;
  publicationDate?: string | null;
  awarenessDate?: string | null;
}

export interface DeadlineStats {
  dueToday: number;
  urgent: number;
  upcoming: number;
  overdue: number;
  completed: number;
  unvalidated: number;
}

export interface DeadlineCalculation {
  startDate: string;
  days: number;
  mode: CountingMode;
  dueDate: string;
  skippedDays: { date: string; reason: string }[];
  notes: string[];
}

export interface CourtCalendarEntry {
  id: string;
  startDate: string;
  endDate: string;
  court: string | null;
  description: string;
  kind: 'FERIADO' | 'SUSPENSAO';
}

export interface CourtCalendar {
  national: { date: string; description: string; national: boolean }[];
  office: CourtCalendarEntry[];
}

// ------------------------------------------------------------------ agenda (Etapa 9)
export type AppointmentType =
  | 'AUDIENCIA' | 'REUNIAO' | 'CONSULTA' | 'DESPACHO' | 'PERICIA' | 'DILIGENCIA' | 'JULGAMENTO' | 'SUSTENTACAO_ORAL'
  | 'COMPROMISSO' | 'ATIVIDADE_INTERNA';

export type AppointmentStatus = 'AGENDADO' | 'REALIZADO' | 'REDESIGNADO' | 'ADIADO' | 'CANCELADO';

export interface Appointment {
  id: string;
  type: AppointmentType;
  typeLabel: string;
  title: string;
  start: string;
  end: string;
  allDay: boolean;
  location: string | null;
  meetingUrl: string | null;
  description: string | null;
  responsibleUids: string[];
  responsibleNames: string[];
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  reminders: number[];
  restricted: boolean;
  seriesId: string | null;
  seriesIndex: number | null;
  recurrence: string | null;
  attendance: Record<string, string> | null;
  guests: { name: string; email: string | null }[] | null;
  status: AppointmentStatus;
  statusLabel: string;
  outcome: string | null;
  meetingPlatform: MeetingPlatform | null;
  meetingPlatformLabel: string | null;
  minutes: string | null;
  minutesUpdatedAt: string | null;
  minutesByName: string | null;
}

export type MeetingPlatform = 'GOOGLE_MEET' | 'ZOOM' | 'TEAMS' | 'PRESENCIAL' | 'OUTRO';

export interface AppointmentRequest {
  type: AppointmentType;
  title: string;
  start: string;
  end: string;
  allDay: boolean;
  location?: string | null;
  meetingUrl?: string | null;
  description?: string | null;
  responsibleUids: string[];
  clientId?: string | null;
  caseId?: string | null;
  reminders: number[];
  guests?: { name: string; email: string | null }[];
  recurrence?: string | null;
  occurrences?: number | null;
  meetingPlatform?: MeetingPlatform | null;
}

export interface CalendarEvent {
  id: string;
  kind: 'APPOINTMENT' | 'DEADLINE';
  type: string;
  typeLabel: string;
  title: string;
  start: string;
  end: string;
  allDay: boolean;
  status: string | null;
  location: string | null;
  caseId: string | null;
  caseNumber: string | null;
  clientName: string | null;
  responsibleNames: string[];
  priority: DeadlinePriority | null;
  validated: boolean | null;
}
