import { Component, inject, input, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { ClientSummary, CursorPage } from '../../core/models/models';

export interface PickedClient { id: string; name: string }

/** Busca de cliente por nome ou CPF/CNPJ com autocompletar (reutilizável em formulários). */
@Component({
  selector: 'app-client-picker',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  template: `
    <mat-form-field appearance="outline" class="full">
      <mat-label>{{ label() }}</mat-label>
      <input matInput [formControl]="search" [matAutocomplete]="auto" placeholder="Nome ou CPF/CNPJ" />
      @if (search.value) {
        <button mat-icon-button matSuffix type="button" (click)="clear()" aria-label="Limpar cliente"><mat-icon>close</mat-icon></button>
      }
      <mat-autocomplete #auto="matAutocomplete" [displayWith]="display" (optionSelected)="pick($event.option.value)">
        @for (o of options(); track o.id) {
          <mat-option [value]="o">{{ o.name }} <span class="muted small">{{ o.documentMasked }}</span></mat-option>
        }
      </mat-autocomplete>
      @if (hint()) { <mat-hint>{{ hint() }}</mat-hint> }
    </mat-form-field>
  `,
  styles: `.full { width: 100%; } .small { font-size: 12px; }`,
})
export class ClientPickerComponent {
  private readonly api = inject(ApiService);
  readonly label = input('Cliente');
  readonly hint = input<string | null>(null);
  readonly initialName = input<string | null>(null);
  readonly picked = output<PickedClient | null>();
  readonly search = new FormControl<string | ClientSummary>('', { nonNullable: true });
  readonly options = toSignal(
    this.search.valueChanges.pipe(
      debounceTime(250), map((v) => (typeof v === 'string' ? v.trim() : '')), distinctUntilChanged(),
      switchMap((term) => term.length < 2 ? of([] as ClientSummary[]) :
        this.api.get<CursorPage<ClientSummary>>('/clients', { search: term, size: 8 }, true).pipe(map((p) => p.items), catchError(() => of([])))),
    ), { initialValue: [] as ClientSummary[] });

  constructor() {
    queueMicrotask(() => {
      const n = this.initialName();
      if (n) this.search.setValue(n, { emitEvent: false });
    });
  }

  display = (c: ClientSummary | string | null): string => (typeof c === 'string' ? c : c?.name ?? '');

  pick(c: ClientSummary): void {
    this.picked.emit({ id: c.id, name: c.name });
  }

  clear(): void {
    this.search.setValue('');
    this.picked.emit(null);
  }
}
