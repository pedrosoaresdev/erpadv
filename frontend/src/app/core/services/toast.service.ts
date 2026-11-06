import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly snack = inject(MatSnackBar);

  success(message: string): void {
    this.snack.open(message, 'OK', { duration: 3500, panelClass: 'snack-success' });
  }

  error(message: string): void {
    this.snack.open(message, 'Fechar', { duration: 6000, panelClass: 'snack-error' });
  }

  info(message: string): void {
    this.snack.open(message, 'OK', { duration: 3500 });
  }
}
