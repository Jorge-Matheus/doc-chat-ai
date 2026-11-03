# ADR 0008: Conversations and follow-up questions

**Status:** Accepted (2026-11-03)

## Context

Answers were lost when the page changed, and each question stood alone, so "and the penalty?" found nothing. Citations named a page, but the reader could not see it. Three choices have lasting effects: how a follow-up is understood, what a citation stores, and how a source is shown.

## Decision

**Follow-ups are rewritten before the search.** When a conversation has earlier turns, the language model rewrites the new question so it stands alone, using the three most recent turns. The rewritten question is used for the search and for the answer, and the prompt that answers is unchanged. The interface shows it as "Searched for", so a wrong interpretation can be seen. A reply that does not look like a question is discarded in favour of the original.

The wording was chosen by judging the final answer, with real search and real models, over 23 follow-ups on three documents. Judging only the rewritten text was misleading: a wording that scored 34 of 36 that way turned "and the penalty?" into "the penalty for ending it with 60 days notice?", which the documents did not answer.

| Wording, judged by the final answer | Correct |
|---|---|
| Rules and three examples | 39 of 46 |
| Earlier questions only, without the answers | 59 of 69 |
| Answers, a rule against adding details, and more examples (adopted) | 62 of 69 |

Of the 7 misses of the adopted wording, 5 came from the answering step refusing after a correct rewrite. Run again over the first set of rewriting cases and 4 attempts to hijack it through an earlier answer, it had 75 of 75 correct.

**A citation points at the passage instead of copying it.** `turn_citations` has a foreign key to `document_chunks` and keeps the file name and page. Deleting a document clears only `document_id`, with `ON DELETE SET NULL (document_id)`, so the passage text really disappears and the citation says the document was deleted. Reprocessing a document would have the same effect, which is safe: a citation can become unavailable, but it can never point at different text.

**A question and its answer are stored together, after the model finishes.** No transaction stays open during the model's work, a failed question leaves nothing behind, and the user gets the question back to try again.

**Sources are shown as text, with the original PDF one click away.** The viewer shows the text extracted from the page, with the cited passage marked. That is exactly what the model read, and a passage is an exact part of its page, so it is found without guessing. A link opens the original file in the browser's own PDF reader, at that page. Rendering PDFs in the application with pdf.js was left out: it adds a dependency of about 37 MB, uses more of the browser's memory, which is the tightest resource on the target machine, and marks passages less exactly.

## Consequences

- A follow-up makes one more call to the model, with a median of about 0.7 seconds. Measured in the browser, on a loaded model, first questions took 1.5 to 2.1 seconds and follow-ups that were answered took 1.9 to 2.5 seconds.
- A rewrite can misread a question, and the answer is then about what was searched. "Searched for" is what lets the user notice.
- Answers come from documents, so the rewriting step reads untrusted text. A manipulated rewrite can only change what is searched, which stays limited to the user's own documents.
- Answers stay in the history after their document is deleted, and they can mention what it said. The confirmation says so, and deleting the conversation removes them. This was the owner's decision.
- Extracted text loses tables and layout, which is why the viewer says so and links to the original.
- `POST /api/questions` was replaced by the conversation endpoints, because no other client existed and an answer outside the history would be lost.
