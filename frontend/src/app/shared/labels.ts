import {
  CaseStatus,
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
  employees: 'Equipe',
  admin: 'Administração',
};

export const UF = [
  'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR', 'PE', 'PI',
  'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO',
];

export function enumOptions<T extends string>(labels: Record<T, string>, exclude: T[] = []): { value: T; label: string }[] {
  return (Object.keys(labels) as T[]).filter((k) => !exclude.includes(k)).map((value) => ({ value, label: labels[value] }));
}

export type { Permission };
