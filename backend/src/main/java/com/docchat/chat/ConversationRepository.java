package com.docchat.chat;

import com.docchat.chat.Turn.TurnCitation;
import com.docchat.rag.AnswerService.Answer;
import com.docchat.rag.AnswerService.Citation;
import com.docchat.rag.AnswerService.PreviousTurn;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Every query joins or filters on the owner, so one user cannot reach another's conversations. */
@Repository
class ConversationRepository {

    private static final String COLUMNS = "id, title, created_at, updated_at";

    private static final RowMapper<Conversation> CONVERSATION_MAPPER = (row, rowNumber) -> new Conversation(
            row.getObject("id", UUID.class),
            row.getString("title"),
            row.getObject("created_at", OffsetDateTime.class).toInstant(),
            row.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;

    ConversationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Stores the conversation with its first turn, so a conversation never exists without one. */
    @Transactional
    public long insertWithFirstTurn(UUID id, UUID ownerId, String title, String question, Answer answer) {
        jdbcClient
                .sql("INSERT INTO conversations (id, owner_id, title) VALUES (:id, :ownerId, :title)")
                .param("id", id)
                .param("ownerId", ownerId)
                .param("title", title)
                .update();
        return insertTurn(id, question, answer);
    }

    /**
     * Touching the conversation first locks its row, so a concurrent delete cannot remove it while
     * the turn is written. Empty if the conversation is not there, which includes it having been
     * deleted while the model was answering.
     */
    @Transactional
    public Optional<Long> appendTurn(UUID id, UUID ownerId, String question, Answer answer) {
        int updated = jdbcClient
                .sql("UPDATE conversations SET updated_at = now() WHERE id = :id AND owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .update();
        return updated == 0 ? Optional.empty() : Optional.of(insertTurn(id, question, answer));
    }

    Optional<Conversation> findOwned(UUID id, UUID ownerId) {
        return jdbcClient
                .sql("SELECT " + COLUMNS + " FROM conversations WHERE id = :id AND owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .query(CONVERSATION_MAPPER)
                .optional();
    }

    List<Conversation> findPageOwnedBy(UUID ownerId, int limit, long offset) {
        return jdbcClient
                .sql("SELECT " + COLUMNS + """
                         FROM conversations WHERE owner_id = :ownerId
                        ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .param("offset", offset)
                .query(CONVERSATION_MAPPER)
                .list();
    }

    long countOwnedBy(UUID ownerId) {
        return jdbcClient
                .sql("SELECT count(*) FROM conversations WHERE owner_id = :ownerId")
                .param("ownerId", ownerId)
                .query(Long.class)
                .single();
    }

    boolean deleteOwned(UUID id, UUID ownerId) {
        return jdbcClient
                        .sql("DELETE FROM conversations WHERE id = :id AND owner_id = :ownerId")
                        .param("id", id)
                        .param("ownerId", ownerId)
                        .update()
                > 0;
    }

    /** @return the most recent turns, oldest first */
    List<PreviousTurn> findRecentTurns(UUID id, UUID ownerId, int limit) {
        List<PreviousTurn> newestFirst = jdbcClient
                .sql("""
                        SELECT t.question, t.answer
                        FROM conversation_turns t
                        JOIN conversations c ON c.id = t.conversation_id
                        WHERE t.conversation_id = :id AND c.owner_id = :ownerId
                        ORDER BY t.id DESC LIMIT :limit
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query((row, rowNumber) -> new PreviousTurn(row.getString("question"), row.getString("answer")))
                .list();
        return newestFirst.reversed();
    }

    /** @return the turns in the order they were asked, with their citations */
    List<Turn> findTurns(UUID id, UUID ownerId) {
        Map<Long, List<TurnCitation>> citations = findCitations(id, ownerId);
        return jdbcClient
                .sql("""
                        SELECT t.id, t.question, t.interpreted_question, t.answer, t.created_at
                        FROM conversation_turns t
                        JOIN conversations c ON c.id = t.conversation_id
                        WHERE t.conversation_id = :id AND c.owner_id = :ownerId
                        ORDER BY t.id
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .query((row, rowNumber) -> {
                    long turnId = row.getLong("id");
                    String answer = row.getString("answer");
                    return new Turn(
                            turnId,
                            row.getString("question"),
                            row.getString("interpreted_question"),
                            answer != null,
                            answer,
                            citations.getOrDefault(turnId, List.of()),
                            row.getObject("created_at", OffsetDateTime.class).toInstant());
                })
                .list();
    }

    /** The excerpt is read from the passage itself, so it disappears along with the document. */
    private Map<Long, List<TurnCitation>> findCitations(UUID id, UUID ownerId) {
        Map<Long, List<TurnCitation>> citations = new HashMap<>();
        jdbcClient
                .sql("""
                        SELECT tc.turn_id, tc.number, tc.document_id, tc.filename, tc.page_number,
                               ch.content AS excerpt
                        FROM turn_citations tc
                        JOIN conversation_turns t ON t.id = tc.turn_id
                        JOIN conversations c ON c.id = t.conversation_id
                        LEFT JOIN document_chunks ch
                            ON ch.document_id = tc.document_id
                            AND ch.page_number = tc.page_number
                            AND ch.chunk_index = tc.chunk_index
                        WHERE t.conversation_id = :id AND c.owner_id = :ownerId
                        ORDER BY tc.turn_id, tc.number
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .query(row -> {
                    citations
                            .computeIfAbsent(row.getLong("turn_id"), unused -> new ArrayList<>())
                            .add(new TurnCitation(
                                    row.getInt("number"),
                                    row.getObject("document_id", UUID.class),
                                    row.getString("filename"),
                                    row.getInt("page_number"),
                                    row.getString("excerpt")));
                });
        return citations;
    }

    private long insertTurn(UUID conversationId, String question, Answer answer) {
        long turnId = jdbcClient
                .sql("""
                        INSERT INTO conversation_turns (conversation_id, question, interpreted_question, answer)
                        VALUES (:conversationId, :question, :interpretedQuestion, :answer)
                        RETURNING id
                        """)
                .param("conversationId", conversationId)
                .param("question", question)
                .param("interpretedQuestion", answer.interpretedQuestion())
                .param("answer", answer.text())
                .query(Long.class)
                .single();
        for (Citation citation : answer.citations()) {
            insertCitation(turnId, citation);
        }
        return turnId;
    }

    /**
     * The document can be deleted while the model is answering. The subquery then gives no id, and
     * the citation is stored the same way as one whose document was deleted later.
     */
    private void insertCitation(long turnId, Citation citation) {
        jdbcClient
                .sql("""
                        INSERT INTO turn_citations (turn_id, number, filename, document_id, page_number, chunk_index)
                        VALUES (:turnId, :number, :filename,
                                (SELECT document_id FROM document_chunks
                                 WHERE document_id = :documentId
                                   AND page_number = :pageNumber
                                   AND chunk_index = :chunkIndex),
                                :pageNumber, :chunkIndex)
                        """)
                .param("turnId", turnId)
                .param("number", citation.number())
                .param("filename", citation.filename())
                .param("documentId", citation.documentId())
                .param("pageNumber", citation.pageNumber())
                .param("chunkIndex", citation.chunkIndex())
                .update();
    }
}
