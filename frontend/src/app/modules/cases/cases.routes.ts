import { Routes } from '@angular/router';

export const CASES_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./cases-list').then((m) => m.CasesListComponent), title: 'Processos · ERPAdv' },
  { path: ':id', loadComponent: () => import('./case-detail').then((m) => m.CaseDetailComponent), title: 'Processo · ERPAdv' },
];
