import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Unsubscribe,
  collection,
  doc,
  limit,
  onSnapshot,
  orderBy,
  query,
  updateDoc,
  where,
} from 'firebase/firestore';
import { FirebaseService } from '../config/firebase.service';
import { AppNotification } from '../models/models';

/**
 * Notificações do usuário em tempo real. Único acesso direto ao Firestore pelo navegador,
 * permitido pelas Security Rules apenas para documentos com recipientUid == uid do token.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly firebase = inject(FirebaseService);
  private unsubscribe: Unsubscribe | null = null;
  private officeId: string | null = null;

  readonly items = signal<AppNotification[]>([]);
  readonly unread = computed(() => this.items().filter((n) => !n.read).length);
  readonly error = signal(false);

  start(officeId: string, uid: string): void {
    this.stop();
    this.officeId = officeId;
    const q = query(
      collection(this.firebase.firestore, 'offices', officeId, 'notifications'),
      where('recipientUid', '==', uid),
      orderBy('createdAt', 'desc'),
      limit(20),
    );
    this.unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        this.error.set(false);
        this.items.set(
          snapshot.docs.map((d) => {
            const data = d.data();
            return {
              id: d.id,
              title: String(data['title'] ?? ''),
              body: String(data['body'] ?? ''),
              link: (data['link'] as string | undefined) ?? null,
              read: Boolean(data['read']),
              createdAt: Number(data['createdAt'] ?? 0),
            };
          }),
        );
      },
      () => this.error.set(true),
    );
  }

  stop(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.items.set([]);
  }

  async markRead(notification: AppNotification): Promise<void> {
    if (notification.read || !this.officeId) return;
    await updateDoc(doc(this.firebase.firestore, 'offices', this.officeId, 'notifications', notification.id), {
      read: true,
      readAt: Date.now(),
    });
  }

  async markAllRead(): Promise<void> {
    await Promise.all(this.items().filter((n) => !n.read).map((n) => this.markRead(n)));
  }
}
