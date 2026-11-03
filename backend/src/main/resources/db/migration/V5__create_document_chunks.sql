-- The dimension must match the embedding model. Changing the model needs a new migration.
CREATE TABLE document_chunks (
    document_id uuid         NOT NULL,
    page_number integer      NOT NULL,
    chunk_index integer      NOT NULL CHECK (chunk_index >= 0),
    content     text         NOT NULL,
    embedding   vector(1024) NOT NULL,
    PRIMARY KEY (document_id, page_number, chunk_index),
    FOREIGN KEY (document_id, page_number)
        REFERENCES document_pages (document_id, page_number) ON DELETE CASCADE
);

-- Documents processed before this migration have no chunks. Marking them as processing makes the
-- backend process them again at startup. Searches run per user over few rows, so they scan exactly
-- and need no approximate vector index.
DELETE FROM document_pages;
UPDATE documents SET status = 'PROCESSING', page_count = NULL WHERE status = 'READY';
