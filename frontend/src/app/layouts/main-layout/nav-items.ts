import { Permission } from '../../core/models/models';

export interface NavItem {
  label: string;
  icon: string;
  route?: string;
  permission: Permission;
  /** Módulo ainda não implantado: aparece desabilitado com a etapa prevista. */
  stage?: number;
}

export interface NavSection {
  title: string;
  items: NavItem[];
}

export const NAV_SECTIONS: NavSection[] = [
  {
    title: 'Escritório',
    items: [
      { label: 'Dashboard', icon: 'space_dashboard', route: '/dashboard', permission: 'DASHBOARD_VIEW' },
      { label: 'Clientes', icon: 'groups', route: '/clientes', permission: 'CLIENTS_READ' },
      { label: 'Processos', icon: 'gavel', route: '/processos', permission: 'CASES_READ' },
      { label: 'Prazos', icon: 'alarm', route: '/prazos', permission: 'DEADLINES_READ' },
      { label: 'Agenda', icon: 'calendar_month', route: '/agenda', permission: 'CALENDAR_READ' },
      { label: 'Quadros', icon: 'view_kanban', route: '/quadros', permission: 'BOARDS_USE' },
      { label: 'CRM', icon: 'filter_alt', route: '/crm', permission: 'CRM_USE' },
    ],
  },
  {
    title: 'Gestão',
    items: [
      { label: 'Financeiro', icon: 'account_balance_wallet', route: '/financeiro', permission: 'FINANCE_READ' },
      { label: 'Contratos', icon: 'handshake', route: '/contratos', permission: 'CONTRACTS_READ' },
      { label: 'Documentos', icon: 'folder_open', route: '/documentos', permission: 'DOCUMENTS_READ' },
      { label: 'Criminal', icon: 'policy', permission: 'CRIMINAL_READ', stage: 12 },
      { label: 'IA Jurídica', icon: 'auto_awesome', permission: 'AI_USE', stage: 13 },
      { label: 'Comunicação', icon: 'forum', permission: 'MESSAGES_USE', stage: 14 },
      { label: 'Relatórios', icon: 'monitoring', permission: 'REPORTS_VIEW', stage: 15 },
    ],
  },
  {
    title: 'Administração',
    items: [
      { label: 'Equipe', icon: 'badge', route: '/equipe', permission: 'EMPLOYEES_READ' },
      { label: 'Auditoria', icon: 'verified_user', route: '/auditoria', permission: 'AUDIT_READ' },
    ],
  },
  {
    title: 'Plataforma',
    items: [
      { label: 'Escritórios', icon: 'admin_panel_settings', route: '/plataforma', permission: 'PLATFORM_ADMIN' },
    ],
  },
];
