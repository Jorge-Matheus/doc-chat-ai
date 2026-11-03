CREATE TABLE documents (
    id             uuid         PRIMARY KEY,
    owner_id       uuid         NOT NULL REFERENCES users (id),
    filename       varchar(255) NOT NULL,
    size_bytes     bigint       NOT NULL CHECK (size_bytes > 0),
    status         varchar(20)  NOT NULL CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    failure_reason varchar(40),
    page_count     integer      CHECK (page_count > 0),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CHECK ((status = 'READY') = (page_count IS NOT NULL))
);

CREATE INDEX documents_owner_recent_idx ON documents (owner_id, created_at DESC, id);

-- Answers will cite page numbers, so text is kept per page. Blank pages are stored too.
CREATE TABLE document_pages (
    document_id uuid    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    page_number integer NOT NULL CHECK (page_number > 0),
    content     text    NOT NULL,
    PRIMARY KEY (document_id, page_number)
);
