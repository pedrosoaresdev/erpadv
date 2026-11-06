import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, forkJoin, map, of, switchMap, tap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ClientSummary, CursorPage, LegalCase } from '../../core/models/models';

interface SearchHit {
  kind: 'client' | 'case';
  id: string;
  title: string;
  subtitle: string;
}

/** Pesquisa global: clientes (nome, CPF/CNPJ, telefone, e-mail) e processos (CNJ, partes, assunto). */
@Component({
  selector: 'app-global-search',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatIconModule],
  template: `
    <div class="search">
      <mat-icon>search</mat-icon>
      <input
        type="search"
        placeholder="Buscar clientes, CPF/CNPJ, processos, nº CNJ…"
        aria-label="Pesquisa global"
        [formControl]="term"
        [matAutocomplete]="auto" />
      @if (loading()) { <span class="dot"></span> }
    </div>
    <mat-autocomplete #auto="matAutocomplete" (optionSelected)="open($event)" [displayWith]="display" panelWidth="460px">
      @if (clients().length) {
        <mat-optgroup label="Clientes">
          @for (hit of clients(); track hit.id) {
            <mat-option [value]="hit"><mat-icon>person</mat-icon><span class="t">{{ hit.title }}</span><span class="s">{{ hit.subtitle }}</span></mat-option>
          }
        </mat-optgroup>
      }
      @if (cases().length) {
        <mat-optgroup label="Processos">
          @for (hit of cases(); track hit.id) {
            <mat-option [value]="hit"><mat-icon>gavel</mat-icon><span class="t">{{ hit.title }}</span><span class="s">{{ hit.subtitle }}</span></mat-option>
          }
        </mat-optgroup>
      }
      @if (searched() && !clients().length && !cases().length && !loading()) {
        <mat-option disabled>Nenhum resultado para “{{ term.value }}”</mat-option>
      }
    </mat-autocomplete>
  `,
  styles: `
    .search { display: flex; align-items: center; gap: 8px; height: 40px; padding: 0 14px; border-radius: 10px;
      background: var(--app-surface-2); border: 1px solid var(--app-border); width: min(520px, 100%); transition: border-color .15s, box-shadow .15s; }
    .search:focus-within { border-color: var(--app-gold); box-shadow: 0 0 0 3px rgb(197 164 109 / 18%); }
    .search mat-icon { color: var(--app-text-muted); font-size: 20px; width: 20px; height: 20px; }
    input { border: 0; outline: 0; background: transparent; flex: 1; font: inherit; color: var(--app-text); min-width: 0; }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--app-gold); animation: pulse 1s infinite; }
    @keyframes pulse { 50% { opacity: .3; } }
    .t { font-weight: 600; margin-right: 8px; }
    .s { color: var(--app-text-muted); font-size: 12px; }
  `,
})
export class GlobalSearchComponent {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly term = new FormControl<string | SearchHit>('', { nonNullable: true });
  readonly clients = signal<SearchHit[]>([]);
  readonly cases = signal<SearchHit[]>([]);
  readonly loading = signal(false);
  readonly searched = signal(false);

  constructor() {
    this.term.valueChanges
      .pipe(
        map((v) => (typeof v === 'string' ? v.trim() : '')),
        debounceTime(300),
        distinctUntilChanged(),
        tap((v) => {
          this.searched.set(false);
          if (v.length < 2) {
            this.clients.set([]);
            this.cases.set([]);
          }
        }),
        switchMap((v) => {
          if (v.length < 2) return of(null);
          this.loading.set(true);
          const empty = of({ items: [], nextCursor: null });
          return forkJoin({
            clients: this.auth.has('CLIENTS_READ')
              ? this.api.get<CursorPage<ClientSummary>>('/clients', { search: v, size: 5 }, true).pipe(catchError(() => empty))
              : empty,
            cases: this.auth.has('CASES_READ')
              ? this.api.get<CursorPage<LegalCase>>('/cases', { search: v, size: 5, includeArchived: true }, true).pipe(catchError(() => empty))
              : empty,
          });
        }),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        this.loading.set(false);
        if (!result) return;
        this.searched.set(true);
        this.clients.set(
          (result.clients.items as ClientSummary[]).map((c) => ({
            kind: 'client' as const, id: c.id, title: c.name, subtitle: c.documentMasked,
          })),
        );
        this.cases.set(
          (result.cases.items as LegalCase[]).map((c) => ({
            kind: 'case' as const, id: c.id, title: c.cnjNumber ?? 'Sem número CNJ', subtitle: c.clientName,
          })),
        );
      });
  }

  display = (value: SearchHit | string | null): string => (typeof value === 'string' ? value : value?.title ?? '');

  open(event: MatAutocompleteSelectedEvent): void {
    const hit = event.option.value as SearchHit;
    this.term.setValue('', { emitEvent: false });
    this.clients.set([]);
    this.cases.set([]);
    void this.router.navigate([hit.kind === 'client' ? '/clientes' : '/processos', hit.id]);
  }
}
