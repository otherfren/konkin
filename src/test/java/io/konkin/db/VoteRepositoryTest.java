package io.konkin.db;

import io.konkin.TestDatabaseManager;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.VoteDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoteRepositoryTest {

    private static final DataSource dataSource = TestDatabaseManager.dataSource("vote_repo_test");
    private VoteRepository repo;
    private ApprovalRequestRepository requestRepo;
    private ChannelRepository channelRepo;

    @BeforeEach
    void setUp() {
        TestDatabaseManager.truncateAll(dataSource);
        repo = new VoteRepository(dataSource);
        requestRepo = new ApprovalRequestRepository(dataSource);
        channelRepo = new ChannelRepository(dataSource);
        seedData();
    }

    private void seedData() {
        Instant now = Instant.now();
        requestRepo.insertApprovalRequest(new ApprovalRequestRow(
                "req-1", "bitcoin", "send_coin", "sess-1", "nonce-1", "hash-1", "comp-1",
                "addr-1", "1.0", null, null, null, "test reason",
                now, now.plusSeconds(3600), "QUEUED", null, null,
                1, 0, 0, "require_approval", now, now, null));
        requestRepo.insertApprovalRequest(new ApprovalRequestRow(
                "req-2", "bitcoin", "send_coin", "sess-2", "nonce-2", "hash-2", "comp-2",
                "addr-2", "2.0", null, null, null, "test reason",
                now, now.plusSeconds(3600), "QUEUED", null, null,
                1, 0, 0, "require_approval", now, now, null));
        channelRepo.insertChannel(new ApprovalChannelRow("ch-1", "web_ui", "Web UI", true, "fp1", now));
        channelRepo.insertChannel(new ApprovalChannelRow("ch-2", "telegram", "Telegram", true, "fp2", now));
    }

    @Test
    void insertAndFindById() {
        Instant now = Instant.now();
        repo.insertVote(new VoteDetail(0L, "req-1", "ch-1", "approve", "Looks good", "admin", now));

        List<VoteDetail> all = repo.listAllVotes();
        assertEquals(1, all.size());

        VoteDetail found = repo.findVoteById(all.getFirst().id());
        assertNotNull(found);
        assertEquals("req-1", found.requestId());
        assertEquals("ch-1", found.channelId());
        assertEquals("approve", found.decision());
        assertEquals("Looks good", found.decisionReason());
        assertEquals("admin", found.decidedBy());
    }

    @Test
    void findVoteById_nonExistent_returnsNull() {
        assertNull(repo.findVoteById(999L));
    }

    @Test
    void updateVote() {
        Instant now = Instant.now();
        repo.insertVote(new VoteDetail(0L, "req-1", "ch-1", "approve", "ok", "admin", now));
        VoteDetail inserted = repo.listAllVotes().getFirst();

        VoteDetail updated = new VoteDetail(inserted.id(), "req-1", "ch-1", "deny", "changed mind", "admin", now);
        repo.updateVote(updated);

        VoteDetail result = repo.findVoteById(inserted.id());
        assertEquals("deny", result.decision());
        assertEquals("changed mind", result.decisionReason());
    }

    @Test
    void deleteVote() {
        Instant now = Instant.now();
        repo.insertVote(new VoteDetail(0L, "req-1", "ch-1", "approve", null, "admin", now));
        VoteDetail inserted = repo.listAllVotes().getFirst();

        assertTrue(repo.deleteVote(inserted.id()));
        assertNull(repo.findVoteById(inserted.id()));
    }

    @Test
    void deleteVote_nonExistent_returnsFalse() {
        assertFalse(repo.deleteVote(999L));
    }

    @Test
    void listVotesForRequest() {
        Instant now = Instant.now();
        repo.insertVote(new VoteDetail(0L, "req-1", "ch-1", "approve", null, "user-a", now));
        repo.insertVote(new VoteDetail(0L, "req-2", "ch-1", "deny", null, "user-b", now));

        List<VoteDetail> votesForReq1 = repo.listVotesForRequest("req-1");
        assertEquals(1, votesForReq1.size());
        assertEquals("approve", votesForReq1.getFirst().decision());

        List<VoteDetail> votesForReq2 = repo.listVotesForRequest("req-2");
        assertEquals(1, votesForReq2.size());

        assertTrue(repo.listVotesForRequest("nonexistent").isEmpty());
    }

    @Test
    void listAllVotes_orderedByDecidedAtDesc() {
        Instant older = Instant.now().minusSeconds(60);
        Instant newer = Instant.now();
        repo.insertVote(new VoteDetail(0L, "req-1", "ch-1", "approve", null, "user-a", older));
        repo.insertVote(new VoteDetail(0L, "req-2", "ch-2", "deny", null, "user-b", newer));

        List<VoteDetail> all = repo.listAllVotes();
        assertEquals(2, all.size());
        assertEquals("req-2", all.get(0).requestId());
        assertEquals("req-1", all.get(1).requestId());
    }

    @Test
    void insertVote_withNullDecisionReason() {
        Instant now = Instant.now();
        repo.insertVote(new VoteDetail(0L, "req-1", "ch-1", "approve", null, "admin", now));

        VoteDetail found = repo.listAllVotes().getFirst();
        assertNull(found.decisionReason());
    }
}
