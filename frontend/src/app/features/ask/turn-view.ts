import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Turn, TurnCitation } from '../../core/conversations/conversation.model';
import { answerParts } from './answer-parts';

/** One question with its answer and sources. Text from documents is only ever bound as text. */
@Component({
  selector: 'app-turn-view',
  imports: [MatButtonModule],
  templateUrl: './turn-view.html',
  styleUrl: './turn-view.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TurnView {
  readonly turn = input.required<Turn>();
  readonly sourceOpened = output<TurnCitation>();

  protected readonly parts = computed(() => {
    const turn = this.turn();
    return answerParts(turn.text ?? '', turn.citations);
  });
}
