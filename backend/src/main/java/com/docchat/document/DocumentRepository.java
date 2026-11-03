package com.docchat.document;

import com.docchat.common.db.PgVectors;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Every query for user-facing data filters by owner, so one user cannot reach another's documents. */
@Repository
public class DocumentRepository {

    private static final String COLUMNS =
            "id, owner_id, filename, size_bytes, status, failure_reason, page_count, created_at";
    private static final int CHUNK_INSERT_BATCH_SIZE = 200;

    private static final RowMapper<Document> ROW_MAPPER = (row, rowNumber) -> {
        String failureReason = row.getString("failure_reason");
        return new Document(
                row.getObject("id", UUID.class),
                row.getObject("owner_id", UUID.class),
                row.getString("filename"),
                row.getLong("size_bytes"),
                DocumentStatus.valueOf(row.getString("status")),
                failureReason == null ? null : DocumentFailureReason.valueOf(failureReason),
                row.getObject("page_count", Integer.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    };

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    DocumentRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    Document insertProcessing(UUID id, UUID ownerId, String filename, long sizeBytes) {
        return jdbcClient
                .sql("""
                        INSERT INTO documents (id, owner_id, filename, size_bytes, status)
                        VALUES (:id, :ownerId, :filename, :sizeBytes, 'PROCESSING')
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("filename", filename)
                .param("sizeBytes", sizeBytes)
                .query(ROW_MAPPER)
                .single();
    }

    Optional<Document> findOwned(UUID id, UUID ownerId) {
        return jdbcClient
                .sql("SELECT " + COLUMNS + " FROM documents WHERE id = :id AND owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .query(ROW_MAPPER)
                .optional();
    }

    List<Document> findPageOwnedBy(UUID ownerId, int limit, long offset) {
        return jdbcClient
                .sql("SELECT " + COLUMNS + """
                         FROM documents WHERE owner_id = :ownerId
                        ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .param("offset", offset)
                .query(ROW_MAPPER)
                .list();
    }

    long countOwnedBy(UUID ownerId) {
        return jdbcClient
                .sql("SELECT count(*) FROM documents WHERE owner_id = :ownerId")
                .param("ownerId", ownerId)
                .query(Long.class)
                .single();
    }

    long totalSizeOwnedBy(UUID ownerId) {
        return jdbcClient
                .sql("SELECT coalesce(sum(size_bytes), 0) FROM documents WHERE owner_id = :ownerId")
                .param("ownerId", ownerId)
                .query(Long.class)
                .single();
    }

    /** Sends a document back to processing if it failed for a temporary reason. */
    Optional<Document> requeueFailed(UUID id, UUID ownerId) {
        return jdbcClient
                .sql("""
                        UPDATE documents SET status = 'PROCESSING', failure_reason = NULL
                        WHERE id = :id AND owner_id = :ownerId AND status = 'FAILED'
                          AND failure_reason IN (:temporaryReasons)
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("temporaryReasons", DocumentFailureReason.temporaryNames())
                .query(ROW_MAPPER)
                .optional();
    }

    Optional<DocumentPage> findPageOwned(UUID id, UUID ownerId, int pageNumber) {
        return jdbcClient
                .sql("""
                        SELECT d.filename, d.page_count, p.content
                        FROM document_pages p
                        JOIN documents d ON d.id = p.document_id
                        WHERE d.id = :id AND d.owner_id = :ownerId AND p.page_number = :pageNumber
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("pageNumber", pageNumber)
                .query((row, rowNumber) -> new DocumentPage(
                        id, row.getString("filename"), pageNumber, row.getInt("page_count"), row.getString("content")))
                .optional();
    }

    boolean deleteOwned(UUID id, UUID ownerId) {
        return jdbcClient
                        .sql("DELETE FROM documents WHERE id = :id AND owner_id = :ownerId")
                        .param("id", id)
                        .param("ownerId", ownerId)
                        .update()
                > 0;
    }

    public List<UUID> findIdsInProcessing() {
        return jdbcClient
                .sql("SELECT id FROM documents WHERE status = 'PROCESSING' ORDER BY created_at")
                .query(UUID.class)
                .list();
    }

    public boolean isProcessing(UUID id) {
        return jdbcClient
                .sql("SELECT count(*) FROM documents WHERE id = :id AND status = 'PROCESSING'")
                .param("id", id)
                .query(Long.class)
                .single()
                > 0;
    }

    /**
     * Saves the extracted pages and marks the document as ready. The update comes first because it
     * locks the row, so a concurrent delete cannot remove the document while its pages are written.
     * It does nothing if the document was deleted during processing.
     */
    @Transactional
    public void completeProcessing(UUID id, List<String> pages, List<EmbeddedChunk> chunks) {
        int updated = jdbcClient
                .sql("""
                        UPDATE documents SET status = 'READY', page_count = :pageCount
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("pageCount", pages.size())
                .update();
        if (updated == 0) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO document_pages (document_id, page_number, content) VALUES (?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        statement.setObject(1, id);
                        statement.setInt(2, index + 1);
                        statement.setString(3, pages.get(index));
                    }

                    @Override
                    public int getBatchSize() {
                        return pages.size();
                    }
                });
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO document_chunks (document_id, page_number, chunk_index, content, embedding)
                VALUES (?, ?, ?, ?, CAST(? AS vector))
                """,
                chunks,
                CHUNK_INSERT_BATCH_SIZE,
                (statement, chunk) -> {
                    statement.setObject(1, id);
                    statement.setInt(2, chunk.pageNumber());
                    statement.setInt(3, chunk.chunkIndex());
                    statement.setString(4, chunk.content());
                    statement.setObject(5, PgVectors.toLiteral(chunk.embedding()), Types.OTHER);
                });
    }

    public void failProcessing(UUID id, DocumentFailureReason reason) {
        jdbcClient
                .sql("""
                        UPDATE documents SET status = 'FAILED', failure_reason = :reason
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("reason", reason.name())
                .update();
    }
}
