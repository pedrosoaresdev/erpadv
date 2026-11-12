import { Permission } from '../../core/models/models';

export interface NavItem {
  label: string;
  icon: string;
  route?: string;
  queryParams?: Record<string, string>;
  /** Permissão exigida; ausente = qualquer integrante da equipe. */
  permission?: Permission;
  /** Alternativa a {@code permission}: basta ter uma destas. */
  anyPermission?: Permission[];
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
      { label: 'Prazos', icon: 'alarm', route: '/prazos', permission: 'DEADLINES_READ' },
      { label: 'Agenda', icon: 'calendar_month', route: '/agenda', permission: 'CALENDAR_READ' },
      { label: 'Audiências', icon: 'record_voice_over', route: '/audiencias', permission: 'CALENDAR_READ' },
      { label: 'Reuniões', icon: 'video_call', route: '/reunioes', permission: 'CALENDAR_READ' },
      { label: 'Tarefas', icon: 'task_alt', route: '/tarefas', permission: 'BOARDS_USE' },
      { label: 'Quadros Kanban', icon: 'view_kanban', route: '/quadros', permission: 'BOARDS_USE' },
      { label: 'CRM', icon: 'filter_alt', route: '/crm', permission: 'CRM_USE' },
    ],
  },
  {
    title: 'Gestão',
    items: [
      { label: 'Financeiro', icon: 'account_balance_wallet', route: '/financeiro', permission: 'FINANCE_READ' },
      { label: 'Honorários', icon: 'payments', route: '/honorarios', permission: 'DASHBOARD_VIEW' },
      { label: 'Contratos', icon: 'handshake', route: '/contratos', permission: 'CONTRACTS_READ' },
      { label: 'Documentos', icon: 'folder_open', route: '/documentos', permission: 'DOCUMENTS_READ' },
      { label: 'Editor Jurídico', icon: 'edit_document', route: '/editor-juridico', permission: 'DOCUMENTS_READ' },
      { label: 'Relatórios', icon: 'monitoring', route: '/relatorios', permission: 'REPORTS_VIEW' },
    ],
  },
  {
    title: 'Atendimento ao cliente',
    items: [
      { label: 'Recibos', icon: 'receipt_long', route: '/recibos', anyPermission: ['FINANCE_READ', 'DOCUMENTS_READ'] },
      { label: 'Notificações', icon: 'notifications', route: '/notificacoes' },
    ],
  },
  {
    title: 'Administração',
    items: [
      { label: 'Equipe', icon: 'badge', route: '/equipe', permission: 'EMPLOYEES_READ' },
      { label: 'Permissões', icon: 'admin_panel_settings', route: '/equipe', queryParams: { aba: 'cargos' }, permission: 'EMPLOYEES_READ' },
      { label: 'Auditoria', icon: 'verified_user', route: '/auditoria', permission: 'AUDIT_READ' },
      { label: 'Segurança', icon: 'shield_lock', route: '/seguranca' },
      { label: 'LGPD', icon: 'privacy_tip', route: '/lgpd', permission: 'LGPD_MANAGE' },
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
