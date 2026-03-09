package io.konkin.db;

import io.konkin.TestDatabaseManager;
import io.konkin.db.entity.ApprovalChannelRow;
import io.konkin.db.entity.ApprovalRequestChannelRow;
import io.konkin.db.entity.ApprovalRequestRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRepositoryTest {

    private static final DataSource dataSource = TestDatabaseManager.dataSource("channel_repo_test");
    private ChannelRepository repo;

    @BeforeEach
    void setUp() {
        TestDatabaseManager.truncateAll(dataSource);
        repo = new ChannelRepository(dataSource);
    }

    // --- ApprovalChannel ---

    @Test
    void insertAndListChannels() {
        Instant now = Instant.now();
        repo.insertChannel(new ApprovalChannelRow("ch-1", "web_ui", "Web UI", true, "fp1", now));
        repo.insertChannel(new ApprovalChannelRow("ch-2", "telegram", "Telegram", false, "fp2", now));

        List<ApprovalChannelRow> channels = repo.listChannels();
        assertEquals(2, channels.size());
        assertEquals("ch-1", channels.get(0).id());
        assertEquals("ch-2", channels.get(1).id());
    }

    @Test
    void findChannelById() {
        Instant now = Instant.now();
        repo.insertChannel(new ApprovalChannelRow("ch-1", "web_ui", "Web UI", true, "fp1", now));

        ApprovalChannelRow found = repo.findChannelById("ch-1");
        assertNotNull(found);
        assertEquals("web_ui", found.channelType());
        assertEquals("Web UI", found.displayName());
        assertTrue(found.enabled());
        assertEquals("fp1", found.configFingerprint());
    }

    @Test
    void findChannelById_nonExistent_returnsNull() {
        assertNull(repo.findChannelById("nonexistent"));
    }

    @Test
    void updateChannel() {
        Instant now = Instant.now();
        repo.insertChannel(new ApprovalChannelRow("ch-1", "web_ui", "Web UI", true, "fp1", now));

        repo.updateChannel(new ApprovalChannelRow("ch-1", "web_ui", "Updated UI", false, "fp2", now));

        ApprovalChannelRow updated = repo.findChannelById("ch-1");
        assertEquals("Updated UI", updated.displayName());
        assertFalse(updated.enabled());
        assertEquals("fp2", updated.configFingerprint());
    }

    @Test
    void deleteChannel() {
        Instant now = Instant.now();
        repo.insertChannel(new ApprovalChannelRow("ch-1", "web_ui", "Web UI", true, "fp1", now));

        assertTrue(repo.deleteChannel("ch-1"));
        assertNull(repo.findChannelById("ch-1"));
    }

    @Test
    void deleteChannel_nonExistent_returnsFalse() {
        assertFalse(repo.deleteChannel("nonexistent"));
    }

    // --- ApprovalRequestChannel ---

    private void seedRequestAndChannel() {
        Instant now = Instant.now();
        ApprovalRequestRepository requestRepo = new ApprovalRequestRepository(dataSource);
        requestRepo.insertApprovalRequest(new ApprovalRequestRow(
                "req-1", "bitcoin", "send_coin", "sess-1", "nonce-1", "hash-1", "comp-1",
                "addr-1", "1.0", null, null, null, "reason",
                now, now.plusSeconds(3600), "QUEUED", null, null,
                1, 0, 0, "require_approval", now, now, null));
        repo.insertChannel(new ApprovalChannelRow("ch-1", "web_ui", "Web UI", true, "fp1", now));
    }

    @Test
    void insertAndListRequestChannels() {
        seedRequestAndChannel();
        Instant now = Instant.now();
        repo.insertRequestChannel(new ApprovalRequestChannelRow(
                0L, "req-1", "ch-1", "queued", now, now, 1, null, now));

        List<ApprovalRequestChannelRow> all = repo.listAllRequestChannels();
        assertEquals(1, all.size());
        assertEquals("req-1", all.getFirst().requestId());
        assertEquals("ch-1", all.getFirst().channelId());
        assertEquals("queued", all.getFirst().deliveryState());
    }

    @Test
    void findRequestChannelById() {
        seedRequestAndChannel();
        Instant now = Instant.now();
        repo.insertRequestChannel(new ApprovalRequestChannelRow(
                0L, "req-1", "ch-1", "sent", now, now, 2, "timeout", now));

        ApprovalRequestChannelRow inserted = repo.listAllRequestChannels().getFirst();
        ApprovalRequestChannelRow found = repo.findRequestChannelById(inserted.id());
        assertNotNull(found);
        assertEquals("sent", found.deliveryState());
        assertEquals(2, found.attemptCount());
        assertEquals("timeout", found.lastError());
    }

    @Test
    void findRequestChannelById_nonExistent_returnsNull() {
        assertNull(repo.findRequestChannelById(999L));
    }

    @Test
    void updateRequestChannel() {
        seedRequestAndChannel();
        Instant now = Instant.now();
        repo.insertRequestChannel(new ApprovalRequestChannelRow(
                0L, "req-1", "ch-1", "queued", now, now, 1, null, now));

        ApprovalRequestChannelRow inserted = repo.listAllRequestChannels().getFirst();
        repo.updateRequestChannel(new ApprovalRequestChannelRow(
                inserted.id(), "req-1", "ch-1", "sent", now, now, 2, null, now));

        ApprovalRequestChannelRow result = repo.findRequestChannelById(inserted.id());
        assertEquals("sent", result.deliveryState());
        assertEquals(2, result.attemptCount());
    }

    @Test
    void deleteRequestChannel() {
        seedRequestAndChannel();
        Instant now = Instant.now();
        repo.insertRequestChannel(new ApprovalRequestChannelRow(
                0L, "req-1", "ch-1", "queued", now, now, 1, null, now));

        ApprovalRequestChannelRow inserted = repo.listAllRequestChannels().getFirst();
        assertTrue(repo.deleteRequestChannel(inserted.id()));
        assertNull(repo.findRequestChannelById(inserted.id()));
    }

    @Test
    void deleteRequestChannel_nonExistent_returnsFalse() {
        assertFalse(repo.deleteRequestChannel(999L));
    }
}
