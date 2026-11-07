import { Routes } from '@angular/router';
import { authGuard, guestGuard, permissionGuard, staffGuard } from './core/auth/guards';
import { AuthLayoutComponent } from './layouts/auth-layout/auth-layout';
import { MainLayoutComponent } from './layouts/main-layout/main-layout';

// A área interna vem primeiro: em "/" o layout de autenticação não deve casar sem filho.
export const routes: Routes = [
  {
    path: 'sem-acesso',
    canActivate: [authGuard],
    title: 'Sem acesso · ERPAdv',
    loadComponent: () => import('./modules/placeholder/no-access').then((m) => m.NoAccessComponent),
  },
  {
    path: '',
    component: MainLayoutComponent,
    canActivate: [authGuard, staffGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', canActivate: [permissionGuard], data: { permission: 'DASHBOARD_VIEW' }, title: 'Dashboard · ERPAdv',
        loadComponent: () => import('./modules/dashboard/dashboard').then((m) => m.DashboardComponent) },
      { path: 'clientes', canActivate: [permissionGuard], data: { permission: 'CLIENTS_READ' },
        loadChildren: () => import('./modules/clients/clients.routes').then((m) => m.CLIENTS_ROUTES) },
      { path: 'processos', canActivate: [permissionGuard], data: { permission: 'CASES_READ' },
        loadChildren: () => import('./modules/cases/cases.routes').then((m) => m.CASES_ROUTES) },
      { path: 'prazos', canActivate: [permissionGuard], data: { permission: 'DEADLINES_READ' }, title: 'Prazos · ERPAdv',
        loadComponent: () => import('./modules/deadlines/deadlines-list').then((m) => m.DeadlinesListComponent) },
      { path: 'agenda', canActivate: [permissionGuard], data: { permission: 'CALENDAR_READ' }, title: 'Agenda · ERPAdv',
        loadComponent: () => import('./modules/calendar/calendar-page').then((m) => m.CalendarPageComponent) },
      { path: 'financeiro', canActivate: [permissionGuard], data: { permission: 'FINANCE_READ' }, title: 'Financeiro · ERPAdv',
        loadComponent: () => import('./modules/finance/finance-page').then((m) => m.FinancePageComponent) },
      { path: 'equipe', canActivate: [permissionGuard], data: { permission: 'EMPLOYEES_READ' }, title: 'Equipe · ERPAdv',
        loadComponent: () => import('./modules/employees/employees-list').then((m) => m.EmployeesListComponent) },
      { path: 'auditoria', canActivate: [permissionGuard], data: { permission: 'AUDIT_READ' }, title: 'Auditoria · ERPAdv',
        loadComponent: () => import('./modules/admin/audit-log').then((m) => m.AuditLogComponent) },
    ],
  },
  {
    path: '',
    component: AuthLayoutComponent,
    children: [
      { path: 'login', canActivate: [guestGuard], title: 'Entrar · ERPAdv',
        loadComponent: () => import('./modules/auth/auth-pages').then((m) => m.LoginComponent) },
      { path: 'cadastro', canActivate: [guestGuard], title: 'Criar conta · ERPAdv',
        loadComponent: () => import('./modules/auth/auth-pages').then((m) => m.RegisterComponent) },
      { path: 'recuperar-senha', title: 'Recuperar acesso · ERPAdv',
        loadComponent: () => import('./modules/auth/auth-pages').then((m) => m.ForgotPasswordComponent) },
      { path: 'verificar-email', canActivate: [authGuard], title: 'Confirmar e-mail · ERPAdv',
        loadComponent: () => import('./modules/auth/auth-pages').then((m) => m.VerifyEmailComponent) },
      { path: 'primeiro-acesso', canActivate: [authGuard], title: 'Configurar escritório · ERPAdv',
        loadComponent: () => import('./modules/onboarding/onboarding').then((m) => m.OnboardingComponent) },
    ],
  },
  { path: '**', redirectTo: '' },
];
