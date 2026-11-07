import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { EmployeeOption } from '../../core/models/models';

export type CardPriority = 'BAIXA' | 'MEDIA' | 'ALTA' | 'URGENTE';

export const CARD_PRIORITY_LABELS: Record<CardPriority, string> = {
  BAIXA: 'Baixa',
  MEDIA: 'Média',
  ALTA: 'Alta',
  URGENTE: 'Urgente',
};

export interface ColumnAutomation {
  notifyAssignees: boolean;
  addLabelId: string | null;
  assignUid: string | null;
  setDueInDays: number | null;
}

export interface BoardColumn {
  id: string;
  name: string;
  color: string | null;
  wipLimit: number;
  done: boolean;
  automation: ColumnAutomation | null;
}

export interface BoardLabel {
  id: string;
  name: string;
  color: string;
}

export interface Board {
  id: string;
  name: string;
  description: string | null;
  color: string | null;
  templateKey: string | null;
  visibility: 'OFFICE' | 'MEMBERS';
  ownerUid: string;
  ownerName: string;
  memberUids: string[];
  columns: BoardColumn[];
  labels: BoardLabel[];
  archived: boolean;
  updatedAt: string | null;
}

export interface ChecklistItem {
  id: string;
  text: string;
  done: boolean;
}

export interface BoardCard {
  id: string;
  boardId: string;
  columnId: string;
  position: number;
  title: string;
  description: string | null;
  assigneeUids: string[];
  labelIds: string[];
  priority: CardPriority;
  startDate: string | null;
  dueDate: string | null;
  completed: boolean;
  completedAt: string | null;
  checklist: ChecklistItem[];
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  deadlineId: string | null;
  estimateHours: number | null;
  columnEnteredAt: string | null;
  lastActivityAt: string | null;
  commentCount: number;
  archived: boolean;
  createdAt: string | null;
}

export interface CardActivity {
  id: string;
  kind: 'COMMENT' | 'EVENT';
  text: string;
  actorUid: string;
  actorName: string;
  at: string;
  mentionUids: string[];
}

export interface BoardSummary {
  id: string;
  name: string;
  description: string | null;
  color: string | null;
  visibility: 'OFFICE' | 'MEMBERS';
  ownerUid: string;
  ownerName: string;
  memberUids: string[];
  columns: number;
  openCards: number;
  archived: boolean;
  canManage: boolean;
  updatedAt: string | null;
}

export interface BoardView {
  board: Board;
  cards: BoardCard[];
  canManage: boolean;
}

export interface BoardTemplate {
  key: string;
  name: string;
  description: string;
  columns: string[];
}

export interface BoardInsights {
  open: number;
  completed: number;
  overdue: number;
  dueThisWeek: number;
  unassigned: number;
  stale: number;
  completed7d: number;
  avgCycleDays: number;
  columns: { columnId: string; name: string; cards: number; wipLimit: number; avgAgeDays: number; overLimit: boolean }[];
  members: { uid: string; name: string; open: number; overdue: number; completed30d: number }[];
  throughputWeeks: number[];
  alerts: string[];
}

export interface MyCard {
  card: BoardCard;
  boardName: string;
  columnName: string;
  boardColor: string | null;
}

export interface BoardRequest {
  name: string;
  description?: string | null;
  color?: string | null;
  visibility: 'OFFICE' | 'MEMBERS';
  memberUids: string[];
  templateKey?: string | null;
}

export interface ColumnRequest {
  id?: string | null;
  name: string;
  color?: string | null;
  wipLimit: number;
  done: boolean;
  notifyAssignees: boolean;
  addLabelId?: string | null;
  assignUid?: string | null;
  setDueInDays?: number | null;
}

export interface CardRequest {
  columnId?: string | null;
  title: string;
  description?: string | null;
  assigneeUids: string[];
  labelIds: string[];
  priority: CardPriority;
  startDate?: string | null;
  dueDate?: string | null;
  checklist: { id?: string | null; text: string; done: boolean }[];
  clientId?: string | null;
  caseId?: string | null;
  estimateHours?: number | null;
}

@Injectable({ providedIn: 'root' })
export class BoardsService {
  private readonly api = inject(ApiService);
  private staff$: Observable<EmployeeOption[]> | null = null;

  /** Equipe ativa (responsáveis e membros), em cache durante a sessão da página. */
  staff(): Observable<EmployeeOption[]> {
    this.staff$ ??= this.api.get<EmployeeOption[]>('/employees/options').pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.staff$;
  }

  list(archived = false): Observable<BoardSummary[]> {
    return this.api.get('/boards', { archived });
  }

  templates(): Observable<BoardTemplate[]> {
    return this.api.get('/boards/templates');
  }

  myCards(): Observable<MyCard[]> {
    return this.api.get('/boards/my-cards');
  }

  create(body: BoardRequest): Observable<BoardView> {
    return this.api.post('/boards', body);
  }

  view(id: string): Observable<BoardView> {
    return this.api.get(`/boards/${encodeURIComponent(id)}`);
  }

  update(id: string, body: BoardRequest): Observable<BoardView> {
    return this.api.put(`/boards/${encodeURIComponent(id)}`, body);
  }

  columns(id: string, columns: ColumnRequest[]): Observable<BoardView> {
    return this.api.put(`/boards/${encodeURIComponent(id)}/columns`, { columns });
  }

  labels(id: string, labels: { id?: string | null; name: string; color: string }[]): Observable<BoardView> {
    return this.api.put(`/boards/${encodeURIComponent(id)}/labels`, { labels });
  }

  archive(id: string, archived: boolean): Observable<BoardView> {
    return this.api.post(`/boards/${encodeURIComponent(id)}/${archived ? 'archive' : 'restore'}`);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/boards/${encodeURIComponent(id)}`);
  }

  insights(id: string): Observable<BoardInsights> {
    return this.api.get(`/boards/${encodeURIComponent(id)}/insights`);
  }

  archivedCards(id: string): Observable<BoardCard[]> {
    return this.api.get(`/boards/${encodeURIComponent(id)}/archived-cards`);
  }

  createCard(boardId: string, body: CardRequest): Observable<BoardCard> {
    return this.api.post(`/boards/${encodeURIComponent(boardId)}/cards`, body);
  }

  card(id: string): Observable<{ card: BoardCard; activity: CardActivity[]; boardName: string }> {
    return this.api.get(`/boards/cards/${encodeURIComponent(id)}`);
  }

  updateCard(id: string, body: CardRequest): Observable<BoardCard> {
    return this.api.put(`/boards/cards/${encodeURIComponent(id)}`, body);
  }

  move(id: string, columnId: string, position: number | null): Observable<BoardCard> {
    return this.api.post(`/boards/cards/${encodeURIComponent(id)}/move`, { columnId, position }, true);
  }

  toggleChecklist(cardId: string, itemId: string): Observable<BoardCard> {
    return this.api.post(`/boards/cards/${encodeURIComponent(cardId)}/checklist/${encodeURIComponent(itemId)}/toggle`);
  }

  archiveCard(id: string, archived: boolean): Observable<BoardCard> {
    return this.api.post(`/boards/cards/${encodeURIComponent(id)}/${archived ? 'archive' : 'restore'}`);
  }

  comment(id: string, text: string, mentionUids: string[]): Observable<CardActivity> {
    return this.api.post(`/boards/cards/${encodeURIComponent(id)}/comments`, { text, mentionUids });
  }

  toDeadline(id: string, body: unknown): Observable<{ card: BoardCard; activity: CardActivity[]; boardName: string }> {
    return this.api.post(`/boards/cards/${encodeURIComponent(id)}/deadline`, body);
  }
}

/** Posição fracionária entre os vizinhos (evita reordenar a coluna inteira). */
export function positionBetween(before: number | null, after: number | null): number {
  if (before == null && after == null) return 1024;
  if (before == null) return (after as number) / 2;
  if (after == null) return before + 1024;
  return (before + after) / 2;
}

/** Dias inteiros desde a data (para "tempo na coluna"). */
export function daysSince(iso: string | null, now = Date.now()): number {
  if (!iso) return 0;
  return Math.max(0, Math.floor((now - new Date(iso).getTime()) / 86_400_000));
}

/** Situação do vencimento em relação a hoje (datas "yyyy-MM-dd"). */
export function dueState(due: string | null, completed: boolean, today = new Date()): 'none' | 'done' | 'overdue' | 'today' | 'soon' | 'later' {
  if (!due) return 'none';
  if (completed) return 'done';
  const t = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
  const [y, m, d] = due.split('-').map(Number);
  const diff = Math.round((new Date(y, m - 1, d).getTime() - t) / 86_400_000);
  if (diff < 0) return 'overdue';
  if (diff === 0) return 'today';
  if (diff <= 3) return 'soon';
  return 'later';
}
