import {
  AppointmentType,
  CaseStatus,
  CountingMode,
  DeadlinePriority,
  DeadlineSituation,
  DeadlineType,
  ClientStatus,
  InteractionChannel,
  LegalArea,
  MovementType,
  Permission,
  Role,
  UserStatus,
} from '../core/models/models';

export const AREA_LABELS: Record<LegalArea, string> = {
  CIVEL: 'Cível',
  TRABALHISTA: 'Trabalhista',
  CRIMINAL: 'Criminal',
  TRIBUTARIO: 'Tributário',
  FAMILIA: 'Família e Sucessões',
  PREVIDENCIARIO: 'Previdenciário',
  EMPRESARIAL: 'Empresarial',
  CONSUMIDOR: 'Consumidor',
  ADMINISTRATIVO: 'Administrativo',
  AMBIENTAL: 'Ambiental',
  OUTRO: 'Outro',
};

export const CASE_STATUS_LABELS: Record<CaseStatus, string> = {
  EM_ANDAMENTO: 'Em andamento',
  AGUARDANDO_JULGAMENTO: 'Aguardando julgamento',
  EM_RECURSO: 'Em recurso',
  SUSPENSO: 'Suspenso',
  ENCERRADO: 'Encerrado',
  ARQUIVADO: 'Arquivado',
};

export const CASE_STATUS_TONE: Record<CaseStatus, string> = {
  EM_ANDAMENTO: 'info',
  AGUARDANDO_JULGAMENTO: 'warning',
  EM_RECURSO: 'gold',
  SUSPENSO: 'warning',
  ENCERRADO: 'success',
  ARQUIVADO: '',
};

export const MOVEMENT_LABELS: Record<MovementType, string> = {
  MOVIMENTACAO: 'Movimentação',
  DESPACHO: 'Despacho',
  DECISAO: 'Decisão',
  SENTENCA: 'Sentença',
  RECURSO: 'Recurso',
  TRANSITO_JULGADO: 'Trânsito em julgado',
  SUSPENSAO: 'Suspensão',
  ACORDAO: 'Acórdão',
  AUDIENCIA: 'Audiência',
  PETICAO: 'Petição',
  NOTA_INTERNA: 'Nota interna',
  SISTEMA: 'Registro do sistema',
};

export const MOVEMENT_ICONS: Record<MovementType, string> = {
  MOVIMENTACAO: 'swap_horiz',
  DESPACHO: 'assignment',
  DECISAO: 'gavel',
  SENTENCA: 'balance',
  RECURSO: 'call_split',
  TRANSITO_JULGADO: 'verified',
  SUSPENSAO: 'pause_circle',
  ACORDAO: 'account_balance',
  AUDIENCIA: 'groups',
  PETICAO: 'description',
  NOTA_INTERNA: 'sticky_note_2',
  SISTEMA: 'settings_suggest',
};

export const CLIENT_STATUS_LABELS: Record<ClientStatus, string> = {
  ACTIVE: 'Ativo',
  INACTIVE: 'Inativo',
};

export const CHANNEL_LABELS: Record<InteractionChannel, string> = {
  PRESENCIAL: 'Presencial',
  TELEFONE: 'Telefone',
  WHATSAPP: 'WhatsApp',
  EMAIL: 'E-mail',
  VIDEOCONFERENCIA: 'Videoconferência',
  OUTRO: 'Outro',
};

export const USER_STATUS_LABELS: Record<UserStatus, string> = {
  INVITED: 'Convidado',
  ACTIVE: 'Ativo',
  BLOCKED: 'Bloqueado',
};

export const ROLE_LABELS: Record<Role, string> = {
  MASTER: 'Master da plataforma',
  SOCIO_ADMIN: 'Sócio administrador',
  ADVOGADO: 'Advogado',
  ADVOGADO_ASSOCIADO: 'Advogado associado',
  ESTAGIARIO: 'Estagiário',
  ASSISTENTE_JURIDICO: 'Assistente jurídico',
  SECRETARIA: 'Secretária',
  FINANCEIRO: 'Financeiro',
  ADMINISTRATIVO: 'Administrativo',
  TI_SUPORTE: 'TI/Suporte',
  CLIENTE: 'Cliente (portal)',
};

export const MODULE_LABELS: Record<string, string> = {
  dashboard: 'Dashboard',
  clients: 'Clientes',
  cases: 'Processos',
  deadlines: 'Prazos',
  calendar: 'Agenda',
  finance: 'Financeiro',
  contracts: 'Contratos',
  documents: 'Documentos',
  criminal: 'Criminal',
  ai: 'Inteligência artificial',
  communication: 'Comunicação',
  reports: 'Relatórios',
  boards: 'Quadros',
  crm: 'CRM comercial',
  employees: 'Equipe',
  admin: 'Administração',
};

export const PHASE_LABELS: Record<import('../core/models/models').CasePhase, string> = {
  POSTULATORIA: 'Postulatória',
  INSTRUTORIA: 'Instrução',
  DECISORIA: 'Sentença/decisão',
  RECURSAL: 'Recursal',
  CUMPRIMENTO: 'Cumprimento de sentença',
  EXECUCAO: 'Execução',
  ENCERRAMENTO: 'Encerramento',
};

export const INSTANCES = ['1ª instância', '2ª instância', 'Juizado Especial', 'Turma Recursal', 'Tribunal Superior (STJ/TST/TSE/STM)', 'STF'];

export const UF = [
  'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR', 'PE', 'PI',
  'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO',
];

export function enumOptions<T extends string>(labels: Record<T, string>, exclude: T[] = []): { value: T; label: string }[] {
  return (Object.keys(labels) as T[]).filter((k) => !exclude.includes(k)).map((value) => ({ value, label: labels[value] }));
}

export type { Permission };

// ------------------------------------------------------------------ Etapa 9

export const DEADLINE_TYPE_LABELS: Record<DeadlineType, string> = {
  CONTESTACAO: 'Contestação',
  REPLICA: 'Réplica',
  RECURSO: 'Recurso',
  CONTRARRAZOES: 'Contrarrazões',
  EMBARGOS_DECLARACAO: 'Embargos de declaração',
  MANIFESTACAO: 'Manifestação',
  CUMPRIMENTO: 'Cumprimento de decisão',
  RESPOSTA_ACUSACAO: 'Resposta à acusação',
  ALEGACOES_FINAIS: 'Alegações finais',
  PRAZO_ADMINISTRATIVO: 'Prazo administrativo',
  OUTRO: 'Outro',
};

export const COUNTING_LABELS: Record<CountingMode, string> = {
  UTEIS: 'Dias úteis (CPC)',
  CORRIDOS: 'Dias corridos',
  MANUAL: 'Data informada manualmente',
};

export const PRIORITY_LABELS: Record<DeadlinePriority, string> = {
  BAIXA: 'Baixa',
  MEDIA: 'Média',
  ALTA: 'Alta',
  CRITICA: 'Crítica',
};

export const SITUATION_LABELS: Record<DeadlineSituation, string> = {
  FUTURO: 'No prazo',
  URGENTE: 'Urgente',
  HOJE: 'Vence hoje',
  VENCIDO: 'Vencido',
  CONCLUIDO: 'Concluído',
  CANCELADO: 'Cancelado',
};

export const SITUATION_TONE: Record<DeadlineSituation, string> = {
  FUTURO: 'info',
  URGENTE: 'warning',
  HOJE: 'danger',
  VENCIDO: 'danger',
  CONCLUIDO: 'success',
  CANCELADO: '',
};

export const APPOINTMENT_LABELS: Record<AppointmentType, string> = {
  AUDIENCIA: 'Audiência',
  REUNIAO: 'Reunião',
  CONSULTA: 'Consulta',
  JULGAMENTO: 'Julgamento',
  SUSTENTACAO_ORAL: 'Sustentação oral',
  COMPROMISSO: 'Compromisso',
  ATIVIDADE_INTERNA: 'Atividade interna',
};

/** Cor por tipo de evento (agenda) — paleta discreta sobre o tema. */
export const EVENT_COLORS: Record<string, string> = {
  AUDIENCIA: '#b42318',
  JULGAMENTO: '#7a3eb1',
  SUSTENTACAO_ORAL: '#9b6a12',
  REUNIAO: '#1d4ed8',
  CONSULTA: '#0f766e',
  COMPROMISSO: '#4b5563',
  ATIVIDADE_INTERNA: '#64748b',
  DEADLINE: '#c5a46d',
};

export const REMINDER_OPTIONS = [
  { value: 15, label: '15 minutos antes' },
  { value: 60, label: '1 hora antes' },
  { value: 180, label: '3 horas antes' },
  { value: 1440, label: '1 dia antes' },
  { value: 2880, label: '2 dias antes' },
];
