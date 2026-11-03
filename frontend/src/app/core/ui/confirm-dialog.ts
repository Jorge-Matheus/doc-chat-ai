import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

export interface ConfirmDialogData {
  readonly title: string;
  /** What is being deleted, shown in bold. */
  readonly subject: string;
  readonly message: string;
}

/** Asks before deleting something, and closes with true when the user confirms. */
@Component({
  selector: 'app-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="subject"><strong>{{ data.subject }}</strong></p>
      <p data-testid="confirm-message">{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" [mat-dialog-close]="false">Cancel</button>
      <button matButton="filled" type="button" class="danger" [mat-dialog-close]="true">Delete</button>
    </mat-dialog-actions>
  `,
  styles: `
    p {
      margin: 0;
    }

    .subject {
      margin-bottom: 0.5rem;
      overflow-wrap: anywhere;
    }

    .danger {
      --mat-button-filled-container-color: var(--color-danger);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
}
