import { Signal, computed, signal } from '@angular/core';

export interface ColumnDef {
  key: string;
  label: string;
  /** Coluna sempre visível (não aparece no seletor de colunas). */
  fixed?: boolean;
}

export type SortDir = 'asc' | 'desc';

const collator = new Intl.Collator('pt-BR', { sensitivity: 'base', numeric: true });

/**
 * Estado padrão das tabelas: seleção individual/múltipla, ordenação por coluna e colunas configuráveis.
 * A preferência de colunas fica salva no navegador do usuário (conveniência por usuário).
 */
export class TableState<T extends { id: string }> {
  readonly selected = signal(new Set<string>());
  readonly sortKey = signal<string | null>(null);
  readonly sortDir = signal<SortDir>('asc');
  readonly hidden = signal(new Set<string>());

  readonly selectedCount = computed(() => this.selected().size);
  readonly sorted: Signal<T[]>;
  readonly allSelected: Signal<boolean>;
  readonly someSelected: Signal<boolean>;

  constructor(
    private readonly rows: Signal<T[]>,
    readonly columns: ColumnDef[],
    private readonly accessor: (row: T, key: string) => string | number | null | undefined,
    private readonly storageKey?: string,
  ) {
    this.hidden.set(new Set(this.read()));
    this.sorted = computed(() => {
      const key = this.sortKey();
      const list = [...this.rows()];
      if (!key) return list;
      const dir = this.sortDir() === 'asc' ? 1 : -1;
      return list.sort((a, b) => {
        const x = this.accessor(a, key);
        const y = this.accessor(b, key);
        if (x == null && y == null) return 0;
        if (x == null) return 1;
        if (y == null) return -1;
        return (typeof x === 'number' && typeof y === 'number' ? x - y : collator.compare(String(x), String(y))) * dir;
      });
    });
    this.allSelected = computed(() => this.rows().length > 0 && this.rows().every((r) => this.selected().has(r.id)));
    this.someSelected = computed(() => this.selected().size > 0 && !this.allSelected());
  }

  visible(key: string): boolean {
    return !this.hidden().has(key);
  }

  toggleColumn(key: string): void {
    const next = new Set(this.hidden());
    if (next.has(key)) next.delete(key); else next.add(key);
    this.hidden.set(next);
    this.write([...next]);
  }

  sortBy(key: string): void {
    if (this.sortKey() === key) {
      this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortKey.set(key);
      this.sortDir.set('asc');
    }
  }

  ariaSort(key: string): 'ascending' | 'descending' | 'none' {
    return this.sortKey() !== key ? 'none' : this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }

  sortIcon(key: string): string {
    return this.sortKey() !== key ? 'unfold_more' : this.sortDir() === 'asc' ? 'arrow_upward' : 'arrow_downward';
  }

  isSelected(id: string): boolean {
    return this.selected().has(id);
  }

  toggle(id: string): void {
    const next = new Set(this.selected());
    if (next.has(id)) next.delete(id); else next.add(id);
    this.selected.set(next);
  }

  toggleAll(): void {
    this.selected.set(this.allSelected() ? new Set() : new Set(this.rows().map((r) => r.id)));
  }

  clear(): void {
    this.selected.set(new Set());
  }

  selectedIds(): string[] {
    return [...this.selected()];
  }

  private read(): string[] {
    if (!this.storageKey) return [];
    try {
      return JSON.parse(localStorage.getItem(this.storageKey) ?? '[]') as string[];
    } catch {
      return [];
    }
  }

  private write(keys: string[]): void {
    if (!this.storageKey) return;
    try {
      localStorage.setItem(this.storageKey, JSON.stringify(keys));
    } catch {
      /* armazenamento indisponível: preferência vale só nesta sessão */
    }
  }
}
