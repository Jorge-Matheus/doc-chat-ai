CREATE TABLE conversations (
    id         uuid        PRIMARY KEY,
    owner_id   uuid        NOT NULL REFERENCES users (id),
    title      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX conversations_owner_recent_idx ON conversations (owner_id, updated_at DESC, id);

-- A question and its answer are stored together once the answer exists, so a question that failed
-- leaves nothing behind. A NULL answer means the documents did not contain it.
CREATE TABLE conversation_turns (
    id                   bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id      uuid        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    question             text        NOT NULL,
    interpreted_question text,
    answer               text,
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX conversation_turns_conversation_idx ON conversation_turns (conversation_id, id);

-- A citation points at the passage instead of copying its text, so deleting a document really
-- removes that text. Only document_id is cleared, which also stops the foreign key from applying:
-- the citation stays, with its file name and page, and can no longer reach any passage.
CREATE TABLE turn_citations (
    turn_id     bigint  NOT NULL REFERENCES conversation_turns (id) ON DELETE CASCADE,
    number      integer NOT NULL CHECK (number > 0),
    filename    text    NOT NULL,
    document_id uuid,
    page_number integer NOT NULL,
    chunk_index integer NOT NULL,
    PRIMARY KEY (turn_id, number),
    FOREIGN KEY (document_id, page_number, chunk_index)
        REFERENCES document_chunks (document_id, page_number, chunk_index)
        ON DELETE SET NULL (document_id)
);

-- Deleting a document checks this table once per passage.
CREATE INDEX turn_citations_chunk_idx ON turn_citations (document_id, page_number, chunk_index);
