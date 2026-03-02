package io.konkin.db;

import io.konkin.db.entity.VoteDetail;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import javax.sql.DataSource;
import java.util.List;

import static io.konkin.db.SqlUtils.toInstant;

public class VoteRepository {

    private static final RowMapper<VoteDetail> VOTE_DETAIL_MAPPER = (rs, ctx) ->
            new VoteDetail(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("channel_id"),
                    rs.getString("decision"),
                    rs.getString("decision_reason"),
                    rs.getString("decided_by"),
                    toInstant(rs.getTimestamp("decided_at"))
            );

    private final Jdbi jdbi;

    public VoteRepository(DataSource dataSource) {
        this.jdbi = JdbiFactory.create(dataSource);
    }

    public List<VoteDetail> listAllVotes() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_votes ORDER BY decided_at DESC")
                .map(VOTE_DETAIL_MAPPER)
                .list());
    }

    public VoteDetail findVoteById(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM approval_votes WHERE id = :id")
                .bind("id", id)
                .map(VOTE_DETAIL_MAPPER)
                .findOne()
                .orElse(null));
    }

    public void insertVote(VoteDetail row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO approval_votes (request_id, channel_id, decision, decision_reason, decided_by, decided_at)
                        VALUES (:requestId, :channelId, :decision, :decisionReason, :decidedBy, :decidedAt)
                        """)
                .bindMethods(row)
                .execute());
    }

    public void updateVote(VoteDetail row) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE approval_votes SET
                            request_id = :requestId, channel_id = :channelId, decision = :decision,
                            decision_reason = :decisionReason, decided_by = :decidedBy, decided_at = :decidedAt
                        WHERE id = :id
                        """)
                .bindMethods(row)
                .execute());
    }

    public boolean deleteVote(long id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM approval_votes WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
    }

    public List<VoteDetail> listVotesForRequest(String requestId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM approval_votes WHERE request_id = :requestId ORDER BY decided_at ASC")
                        .bind("requestId", requestId)
                        .map(VOTE_DETAIL_MAPPER)
                        .list()
        );
    }
}
