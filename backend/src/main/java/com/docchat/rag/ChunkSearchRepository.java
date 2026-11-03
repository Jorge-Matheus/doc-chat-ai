package com.docchat.rag;

import com.docchat.common.db.PgVectors;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Always filters by owner, so a search can only ever return the user's own passages. */
@Repository
class ChunkSearchRepository {

    private static final String SEARCH = """
            SELECT c.document_id, d.filename, c.page_number, c.chunk_index, c.content,
                   1 - (c.embedding <=> CAST(:embedding AS vector)) AS similarity
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.owner_id = :ownerId AND d.status = 'READY' %s
            ORDER BY c.embedding <=> CAST(:embedding AS vector), c.document_id, c.page_number, c.chunk_index
            LIMIT :limit
            """;

    private final JdbcClient jdbcClient;

    ChunkSearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** @param documentIds limits the search to these documents, or to all of them when empty */
    List<RetrievedChunk> findClosest(UUID ownerId, List<UUID> documentIds, float[] embedding, int limit) {
        boolean filtered = !documentIds.isEmpty();
        JdbcClient.StatementSpec statement = jdbcClient
                .sql(SEARCH.formatted(filtered ? "AND d.id IN (:documentIds)" : ""))
                .param("embedding", PgVectors.toLiteral(embedding), Types.OTHER)
                .param("ownerId", ownerId)
                .param("limit", limit);
        if (filtered) {
            statement = statement.param("documentIds", documentIds);
        }
        return statement
                .query((row, rowNumber) -> new RetrievedChunk(
                        row.getObject("document_id", UUID.class),
                        row.getString("filename"),
                        row.getInt("page_number"),
                        row.getInt("chunk_index"),
                        row.getString("content"),
                        row.getDouble("similarity")))
                .list();
    }
}
