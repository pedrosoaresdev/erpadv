/** Utilitários de datas da agenda (fuso local do navegador; semana inicia no domingo). */

export type CalendarView = 'day' | 'week' | 'month';

export const DAY_MS = 86_400_000;
export const HOUR_PX = 48;

export function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

export function addDays(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, d.getHours(), d.getMinutes());
}

export function startOfWeek(d: Date): Date {
  const s = startOfDay(d);
  return addDays(s, -s.getDay());
}

export function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** 6 semanas (42 dias) cobrindo o mês de {@code ref}. */
export function monthGrid(ref: Date): Date[] {
  const first = new Date(ref.getFullYear(), ref.getMonth(), 1);
  const start = startOfWeek(first);
  return Array.from({ length: 42 }, (_, i) => addDays(start, i));
}

export function weekDays(ref: Date): Date[] {
  const start = startOfWeek(ref);
  return Array.from({ length: 7 }, (_, i) => addDays(start, i));
}

/** Intervalo [from, to) a consultar para a visão. */
export function rangeFor(view: CalendarView, ref: Date): { from: Date; to: Date } {
  if (view === 'day') {
    const from = startOfDay(ref);
    return { from, to: addDays(from, 1) };
  }
  if (view === 'week') {
    const from = startOfWeek(ref);
    return { from, to: addDays(from, 7) };
  }
  const grid = monthGrid(ref);
  return { from: grid[0], to: addDays(grid[41], 1) };
}

export function shift(view: CalendarView, ref: Date, direction: 1 | -1): Date {
  if (view === 'day') return addDays(ref, direction);
  if (view === 'week') return addDays(ref, 7 * direction);
  return new Date(ref.getFullYear(), ref.getMonth() + direction, 1);
}

/** Eventos que ocorrem no dia (início no dia, ou atravessando-o). */
export function occursOn<T extends { start: string; end: string }>(events: T[], day: Date): T[] {
  const dayStart = startOfDay(day).getTime();
  const dayEnd = dayStart + DAY_MS;
  return events.filter((e) => {
    const s = new Date(e.start).getTime();
    const end = Math.max(new Date(e.end).getTime(), s + 1);
    return s < dayEnd && end > dayStart;
  });
}

/** Posição vertical (px) de um evento com horário na grade do dia. */
export function timeBox(start: string, end: string, day: Date): { top: number; height: number } {
  const dayStart = startOfDay(day).getTime();
  const s = Math.max(new Date(start).getTime(), dayStart);
  const e = Math.min(new Date(end).getTime(), dayStart + DAY_MS);
  const top = ((s - dayStart) / 3_600_000) * HOUR_PX;
  const height = Math.max(((e - s) / 3_600_000) * HOUR_PX, 22);
  return { top, height };
}

export function toLocalDateInput(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function toLocalTimeInput(d: Date): string {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function fromLocal(date: string, time: string): Date {
  const [y, m, d] = date.split('-').map(Number);
  const [h, min] = time.split(':').map(Number);
  return new Date(y, m - 1, d, h, min);
}
