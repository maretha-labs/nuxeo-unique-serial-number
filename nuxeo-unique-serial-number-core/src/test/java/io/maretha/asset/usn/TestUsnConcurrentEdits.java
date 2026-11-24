package io.maretha.asset.usn;

import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_GENERATE_USN;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_DATE;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_LONG;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_STRING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.google.inject.Inject;

import io.maretha.asset.usn.service.UsnAllocationException;
import io.maretha.asset.usn.service.UsnService;

/** Real sessions, separate JTA transactions, and a fresh reader after completion. */
@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.asset.usn.core")
public class TestUsnConcurrentEdits {

    @Inject protected CoreSession session;
    @Inject protected UsnService service;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private String repository;

    @Before
    public void selectRepository() {
        repository = session.getRepositoryName();
    }

    @After
    public void stopWorkers() {
        workers.shutdownNow();
    }

    private DocumentRef createAsset(LocalDate date) {
        DocumentModel asset = session.createDocumentModel("/", "race-" + UUID.randomUUID(), DOCTYPE_ASSET);
        asset.setPropertyValue("dc:title", "Original title");
        asset.setPropertyValue(PROP_USN_DATE, service.toUtcNoonCalendar(date));
        DocumentRef ref = session.createDocument(asset).getRef();
        session.save();
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
        return ref;
    }

    private <T> T inSession(Function<CoreSession, T> work) {
        return TransactionHelper.runInNewTransaction(() -> {
            try (CloseableCoreSession local = CoreInstance.openCoreSessionSystem(repository)) {
                return work.apply(local);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Concurrent transaction did not reach its barrier", latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static String assign(CoreSession local, DocumentModel asset) {
        asset.setPropertyValue(PROP_GENERATE_USN, true);
        asset = local.saveDocument(asset);
        local.save();
        String number = (String) asset.getPropertyValue(PROP_USN_STRING);
        assertNotNull(number);
        return number;
    }

    private record AssetState(String text, Long number, String title) { }

    private void verifyPersisted(DocumentRef ref, String number) {
        AssetState persisted = inSession(local -> {
            DocumentModel doc = local.getDocument(ref);
            return new AssetState((String) doc.getPropertyValue(PROP_USN_STRING),
                    (Long) doc.getPropertyValue(PROP_USN_LONG), doc.getTitle());
        });
        assertEquals("A previously committed USN must survive subsequent transaction completion",
                new AssetState(number, Long.valueOf(number), "Original title"), persisted);
    }

    @Test
    public void exactlyOneOverlappingGenerateCommits() throws Exception {
        DocumentRef ref = createAsset(LocalDate.of(2030, 1, 14));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch attempted = new CountDownLatch(2);
        Callable<String> race = () -> inSession(local -> {
            DocumentModel asset = local.getDocument(ref);
            assertNull(asset.getPropertyValue(PROP_USN_STRING));
            loaded.countDown();
            await(loaded);
            String number = null;
            try {
                number = assign(local, asset);
            } catch (UsnAllocationException e) {
                assertEquals(409, e.getStatusCode());
                assertTrue(e.getMessage(), e.getMessage().contains("assignment is already in progress"));
                assertTrue(TransactionHelper.isTransactionMarkedRollback());
            } finally {
                attempted.countDown();
            }
            await(attempted); // The winner retains its lock until the other worker has attempted assignment.
            return number;
        });
        Future<String> first = workers.submit(race);
        Future<String> second = workers.submit(race);
        String a = first.get(35, TimeUnit.SECONDS);
        String b = second.get(35, TimeUnit.SECONDS);
        assertTrue("Exactly one transaction must assign", (a == null) != (b == null));
        verifyPersisted(ref, a != null ? a : b);
    }

    /** Opt-in production gate for a platform whose rollback may replay a whole-document preimage. */
    @Test
    public void unrelatedFlushedEditRollbackPreservesCommittedAssignment() throws Exception {
        assumeTrue("Enable with -Dusn.test.rollbackRace=true", Boolean.getBoolean("usn.test.rollbackRace"));
        DocumentRef ref = createAsset(LocalDate.of(2030, 1, 15));
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch releaseRollback = new CountDownLatch(1);
        Future<?> ordinary = workers.submit(() -> inSession(local -> {
            DocumentModel asset = local.getDocument(ref);
            asset.setPropertyValue("dc:title", "Uncommitted title");
            local.saveDocument(asset);
            local.save();
            flushed.countDown();
            await(releaseRollback);
            TransactionHelper.setTransactionRollbackOnly();
            return null;
        }));
        Future<String> assignment = workers.submit(() -> {
            await(flushed);
            return inSession(local -> assign(local, local.getDocument(ref)));
        });
        String winner;
        try {
            winner = assignment.get(30, TimeUnit.SECONDS);
            assertEquals(winner, inSession(local -> local.getDocument(ref).getPropertyValue(PROP_USN_STRING)));
        } finally {
            releaseRollback.countDown();
            ordinary.get(30, TimeUnit.SECONDS);
        }
        verifyPersisted(ref, winner);
    }
}
