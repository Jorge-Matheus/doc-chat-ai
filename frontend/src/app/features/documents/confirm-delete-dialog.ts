import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

export interface ConfirmDeleteData {
  readonly filename: string;
}

/** Closes with true when the user confirms. */
@Component({
  selector: 'app-confirm-delete-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Delete this document?</h2>
    <mat-dialog-content>
      <strong>{{ data.filename }}</strong> and its extracted text will be permanently deleted.
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" [mat-dialog-close]="false">Cancel</button>
      <button matButton="filled" type="button" [mat-dialog-close]="true">Delete</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDeleteDialog {
  protected readonly data = inject<ConfirmDeleteData>(MAT_DIALOG_DATA);
}
