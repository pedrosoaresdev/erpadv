import { Permission } from '../../core/models/models';

export interface NavItem {
  label: string;
  icon: string;
  route?: string;
  queryParams?: Record<string, string>;
  /** Permissão exigida; ausente = qualquer integrante da equipe. */
  permission?: Permission;
  /** Módulo ainda não implantado: aparece desabilitado com a fase prevista do roadmap. */
  phase?: number;
}

export interface NavSection {
  title: string;
  items: NavItem[];
}

/** Menu lateral final do ERPAdv (especificação, seção 23). */
export const NAV_SECTIONS: NavSection[] = [
  {
    title: 'Escritório',
    items: [
      { label: 'Dashboard', icon: 'space_dashboard', route: '/dashboard', permission: 'DASHBOARD_VIEW' },
      { label: 'Clientes', icon: 'groups', route: '/clientes', permission: 'CLIENTS_READ' },
      { label: 'Processos', icon: 'gavel', route: '/processos', permission: 'CASES_READ' },
      { label: 'Central Judicial', icon: 'account_balance', permission: 'CASES_READ', phase: 4 },
      { label: 'Prazos', icon: 'alarm', route: '/prazos', permission: 'DEADLINES_READ' },
      { label: 'Agenda', icon: 'calendar_month', route: '/agenda', permission: 'CALENDAR_READ' },
      { label: 'Audiências', icon: 'record_voice_over', permission: 'CALENDAR_READ', phase: 2 },
      { label: 'Tarefas', icon: 'task_alt', permission: 'BOARDS_USE', phase: 2 },
      { label: 'Quadros Kanban', icon: 'view_kanban', route: '/quadros', permission: 'BOARDS_USE' },
      { label: 'CRM', icon: 'filter_alt', route: '/crm', permission: 'CRM_USE' },
    ],
  },
  {
    title: 'Gestão',
    items: [
      { label: 'Financeiro', icon: 'account_balance_wallet', route: '/financeiro', permission: 'FINANCE_READ' },
      { label: 'Honorários', icon: 'payments', permission: 'CONTRACTS_READ', phase: 3 },
      { label: 'Contratos', icon: 'handshake', route: '/contratos', permission: 'CONTRACTS_READ' },
      { label: 'Documentos', icon: 'folder_open', route: '/documentos', permission: 'DOCUMENTS_READ' },
      { label: 'Editor Jurídico', icon: 'edit_document', permission: 'DOCUMENTS_WRITE', phase: 2 },
      { label: 'Relatórios', icon: 'monitoring', permission: 'REPORTS_VIEW', phase: 3 },
    ],
  },
  {
    title: 'Inteligência',
    items: [
      { label: 'IA Jurídica', icon: 'auto_awesome', permission: 'AI_USE', phase: 5 },
      { label: 'Pesquisa Jurídica', icon: 'travel_explore', permission: 'AI_USE', phase: 5 },
      { label: 'Automação de Rotinas', icon: 'bolt', permission: 'SETTINGS_MANAGE', phase: 5 },
    ],
  },
  {
    title: 'Comunicação',
    items: [
      { label: 'Atendimento', icon: 'support_agent', permission: 'MESSAGES_USE', phase: 4 },
      { label: 'WhatsApp', icon: 'chat', permission: 'MESSAGES_USE', phase: 4 },
      { label: 'E-mails', icon: 'mail', permission: 'MESSAGES_USE', phase: 4 },
      { label: 'Notificações', icon: 'notifications', route: '/notificacoes' },
    ],
  },
  {
    title: 'Administração',
    items: [
      { label: 'Equipe', icon: 'badge', route: '/equipe', permission: 'EMPLOYEES_READ' },
      { label: 'Permissões', icon: 'admin_panel_settings', route: '/equipe', queryParams: { aba: 'cargos' }, permission: 'EMPLOYEES_READ' },
      { label: 'Auditoria', icon: 'verified_user', route: '/auditoria', permission: 'AUDIT_READ' },
      { label: 'Segurança', icon: 'shield_lock', permission: 'SETTINGS_MANAGE', phase: 5 },
      { label: 'Integrações', icon: 'hub', permission: 'SETTINGS_MANAGE', phase: 4 },
      { label: 'Configurações', icon: 'settings', permission: 'SETTINGS_MANAGE', phase: 6 },
      { label: 'Plano e Assinatura', icon: 'workspace_premium', permission: 'SETTINGS_MANAGE', phase: 6 },
    ],
  },
  {
    title: 'Plataforma',
    items: [
      { label: 'Escritórios', icon: 'domain', route: '/plataforma', permission: 'PLATFORM_ADMIN' },
    ],
  },
];

/** Atalhos da barra inferior no celular, na ordem de preferência (os 4 primeiros permitidos). */
export const MOBILE_SHORTCUTS: NavItem[] = [
  { label: 'Início', icon: 'space_dashboard', route: '/dashboard', permission: 'DASHBOARD_VIEW' },
  { label: 'Processos', icon: 'gavel', route: '/processos', permission: 'CASES_READ' },
  { label: 'Prazos', icon: 'alarm', route: '/prazos', permission: 'DEADLINES_READ' },
  { label: 'Agenda', icon: 'calendar_month', route: '/agenda', permission: 'CALENDAR_READ' },
  { label: 'Clientes', icon: 'groups', route: '/clientes', permission: 'CLIENTS_READ' },
  { label: 'Quadros', icon: 'view_kanban', route: '/quadros', permission: 'BOARDS_USE' },
  { label: 'CRM', icon: 'filter_alt', route: '/crm', permission: 'CRM_USE' },
  { label: 'Financeiro', icon: 'account_balance_wallet', route: '/financeiro', permission: 'FINANCE_READ' },
  { label: 'Escritórios', icon: 'domain', route: '/plataforma', permission: 'PLATFORM_ADMIN' },
];
