import { signal } from '@angular/core';
import { notificationKind } from '../../modules/notifications/notifications-page';
import { TableState } from './table-state';

interface Row { id: string; name: string; value: number | null }

describe('estado padrão das tabelas', () => {
  const rows = signal<Row[]>([
    { id: 'a', name: 'Érica', value: 30 },
    { id: 'b', name: 'ana', value: null },
    { id: 'c', name: 'Bruno', value: 10 },
  ]);
  const make = () => new TableState<Row>(rows, [{ key: 'name', label: 'Nome', fixed: true }, { key: 'value', label: 'Valor' }],
    (r, k) => (k === 'name' ? r.name : r.value));

  it('ordena ignorando acentos e caixa, alternando a direção', () => {
    const t = make();
    t.sortBy('name');
    expect(t.sorted().map((r) => r.id)).toEqual(['b', 'c', 'a']);
    t.sortBy('name');
    expect(t.sorted().map((r) => r.id)).toEqual(['a', 'c', 'b']);
    expect(t.ariaSort('name')).toBe('descending');
  });

  it('valores vazios vão para o fim', () => {
    const t = make();
    t.sortBy('value');
    expect(t.sorted().map((r) => r.id)).toEqual(['c', 'a', 'b']);
  });

  it('seleção individual e de todos', () => {
    const t = make();
    t.toggle('a');
    expect(t.selectedCount()).toBe(1);
    expect(t.someSelected()).toBeTrue();
    t.toggleAll();
    expect(t.allSelected()).toBeTrue();
    expect(t.selectedIds().sort()).toEqual(['a', 'b', 'c']);
    t.toggleAll();
    expect(t.selectedCount()).toBe(0);
  });

  it('colunas podem ser ocultadas', () => {
    const t = make();
    expect(t.visible('value')).toBeTrue();
    t.toggleColumn('value');
    expect(t.visible('value')).toBeFalse();
  });
});

describe('central de notificações', () => {
  it('classifica prioridade e categoria pelo tipo', () => {
    expect(notificationKind('DEADLINE_ALERT')).toEqual(jasmine.objectContaining({ label: 'Prazos', priority: 'ALTA' }));
    expect(notificationKind('CASE_MOVEMENT').priority).toBe('ALTA');
    expect(notificationKind('BOARD_COMMENT').priority).toBe('BAIXA');
    expect(notificationKind('DESCONHECIDO').label).toBe('Outros');
  });
});
