import { Routes } from '@angular/router';

export const CLIENTS_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./clients-list').then((m) => m.ClientsListComponent), title: 'Clientes · ERPAdv' },
  { path: ':id', loadComponent: () => import('./client-detail').then((m) => m.ClientDetailComponent), title: 'Cliente · ERPAdv' },
];
