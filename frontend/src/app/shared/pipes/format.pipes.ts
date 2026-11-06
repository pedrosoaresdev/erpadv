import { Pipe, PipeTransform } from '@angular/core';

const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const DATE = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' });
const DATETIME = new Intl.DateTimeFormat('pt-BR', {
  day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
});

/** Valores monetários armazenados em centavos → "R$ 1.234,56". */
@Pipe({ name: 'cents' })
export class CentsPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : BRL.format(value / 100);
  }
}

/** Datas ISO ("2024-05-10" ou instantes) → "10/05/2024". */
@Pipe({ name: 'brDate' })
export class BrDatePipe implements PipeTransform {
  transform(value: string | number | null | undefined, withTime = false): string {
    if (value === null || value === undefined || value === '') return '—';
    const date =
      typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
        ? new Date(`${value}T12:00:00`)
        : new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return (withTime ? DATETIME : DATE).format(date);
  }
}

/** Tempo relativo curto: "há 5 min", "há 2 dias". */
@Pipe({ name: 'relative' })
export class RelativeTimePipe implements PipeTransform {
  transform(value: string | number | null | undefined): string {
    if (!value) return '—';
    const diff = Date.now() - new Date(value).getTime();
    const minutes = Math.round(diff / 60000);
    if (minutes < 1) return 'agora';
    if (minutes < 60) return `há ${minutes} min`;
    const hours = Math.round(minutes / 60);
    if (hours < 24) return `há ${hours} h`;
    const days = Math.round(hours / 24);
    if (days < 30) return `há ${days} dia${days > 1 ? 's' : ''}`;
    return DATE.format(new Date(value));
  }
}
