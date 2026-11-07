import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap, tap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api/api.service';

export interface SearchHit {
  id: string;
  title: string;
  subtitle: string;
  link: string | null;
}

export interface SearchGroup {
  key: string;
  label: string;
  icon: string;
  items: SearchHit[];
}

/**
 * Pesquisa global: clientes, CPF/CNPJ, processos, nº CNJ, advogados, documentos, contratos, prazos,
 * audiências, tarefas e movimentações — agrupados por categoria e filtrados pelas permissões no servidor.
 */
@Component({
  selector: 'app-global-search',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatIconModule],
  template: `
    <div class="search">
      <mat-icon>search</mat-icon>
      <input
        type="search"
        placeholder="Buscar clientes, CPF/CNPJ, processos, CNJ, documentos, prazos…"
        aria-label="Pesquisa global"
        [formControl]="term"
        [matAutocomplete]="auto" />
      @if (loading()) { <span class="dot"></span> }
    </div>
    <mat-autocomplete #auto="matAutocomplete" (optionSelected)="open($event)" [displayWith]="display" panelWidth="520px">
      @for (group of groups(); track group.key) {
        <mat-optgroup [label]="group.label">
          @for (hit of group.items; track hit.id) {
            <mat-option [value]="hit" [disabled]="!hit.link">
              <mat-icon>{{ group.icon }}</mat-icon><span class="t">{{ hit.title }}</span><span class="s">{{ hit.subtitle }}</span>
            </mat-option>
          }
        </mat-optgroup>
      }
      @if (searched() && !groups().length && !loading()) {
        <mat-option disabled>Nenhum resultado para “{{ term.value }}”</mat-option>
      }
    </mat-autocomplete>
  `,
  styles: `
    .search { display: flex; align-items: center; gap: 8px; height: 40px; padding: 0 14px; border-radius: 10px;
      background: var(--app-surface-2); border: 1px solid var(--app-border); width: min(520px, 100%); transition: border-color .15s, box-shadow .15s; box-sizing: border-box; }
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
  private readonly router = inject(Router);

  readonly term = new FormControl<string | SearchHit>('', { nonNullable: true });
  readonly groups = signal<SearchGroup[]>([]);
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
          if (v.length < 2) this.groups.set([]);
        }),
        switchMap((v) => {
          if (v.length < 2) return of(null);
          this.loading.set(true);
          return this.api.get<SearchGroup[]>('/search', { q: v }, true).pipe(catchError(() => of([] as SearchGroup[])));
        }),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        this.loading.set(false);
        if (!result) return;
        this.searched.set(true);
        this.groups.set(result);
      });
  }

  display = (value: SearchHit | string | null): string => (typeof value === 'string' ? value : value?.title ?? '');

  open(event: MatAutocompleteSelectedEvent): void {
    const hit = event.option.value as SearchHit;
    this.term.setValue('', { emitEvent: false });
    this.groups.set([]);
    if (hit.link) void this.router.navigateByUrl(hit.link);
  }
}
