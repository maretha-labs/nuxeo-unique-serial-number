package io.maretha.asset.usn;

import static io.maretha.asset.usn.AssetUsnConstants.PROP_GENERATE_USN;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_DATE;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_LONG;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_STRING;
import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.google.inject.Inject;
import io.maretha.asset.usn.service.UsnService;

/**
 * Run two independent Nuxeo test runtimes with separate document repositories and the same disposable
 * H2-backed platform KV store. Set usn.test.kvUrl, usn.test.pairDir and usn.test.node (a or b).
 * Start the second runtime after the first publishes its .ready file.
 */
@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.asset.usn.core")
@Deploy("io.maretha.asset.usn.tests:OSGI-INF/two-node-allocator.xml")
public class TwoNodeAssetCreationIT {
    private static final int CREATES = 160;
    private static final String DAY = "20300119";

    @Inject protected CoreSession session;
    @Inject protected UsnService service;

    private Path directory;
    private String node;
    private String peer;

    private record Saved(String id, String text, Long number) {
        String csv() { return String.join(",", id, text, number.toString()); }
    }

    private static Saved from(DocumentModel asset) {
        return new Saved(asset.getId(), (String) asset.getPropertyValue(PROP_USN_STRING),
                (Long) asset.getPropertyValue(PROP_USN_LONG));
    }

    private <T> T fresh(Function<CoreSession, T> work) {
        String repository = session.getRepositoryName();
        return TransactionHelper.runInNewTransaction(() -> {
            try (CloseableCoreSession local = CoreInstance.openCoreSessionSystem(repository)) {
                return work.apply(local);
            }
        });
    }

    private void publish(String suffix, List<String> lines) throws Exception {
        Path target = directory.resolve(node + suffix);
        assertFalse("Use a new empty coordination directory for each run", Files.exists(target));
        Path temp = Files.createTempFile(directory, node, ".tmp");
        Files.write(temp, lines);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path await(String worker, String suffix) throws InterruptedException {
        Path path = directory.resolve(worker + suffix);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!Files.exists(path)) {
            assertTrue("Timed out waiting for " + path, System.nanoTime() < deadline);
            Thread.sleep(25);
        }
        return path;
    }

    private void barrier(String suffix) throws Exception {
        publish(suffix, List.of());
        await(peer, suffix);
    }

    @Test
    public void concurrentlyCreateAndVerifyCommittedPairsAcrossJvms() throws Exception {
        node = System.getProperty("usn.test.node");
        assertTrue("Start a and b concurrently", List.of("a", "b").contains(node));
        peer = node.equals("a") ? "b" : "a";
        directory = Path.of(System.getProperty("usn.test.pairDir"));
        var store = Framework.getService(KeyValueService.class).getKeyValueStore("default");
        long pid = ProcessHandle.current().pid();
        publish(".ready", List.of(Long.toString(pid)));
        assertNotEquals(Long.toString(pid), Files.readString(await(peer, ".ready")).trim());
        List<Saved> created = new ArrayList<>();
        for (int index = 0; index < CREATES; index++) {
            if (index % 32 == 0) barrier(".start-" + index);
            String name = "asset-" + node + "-" + index;
            Saved result = fresh(local -> {
                DocumentModel asset = local.createDocumentModel("/", name, "Asset");
                asset.setPropertyValue(PROP_USN_DATE, service.toUtcNoonCalendar(LocalDate.of(2030, 1, 19)));
                asset.setPropertyValue(PROP_GENERATE_USN, true);
                return from(local.createDocument(asset));
            });
            assertTrue(result.text(), result.text().matches(DAY + "[0-9]{6}"));
            assertEquals(Long.valueOf(result.text()), result.number());
            created.add(result);
        }
        List<String> verified = new ArrayList<>();
        for (Saved expected : created) {
            Saved stored = fresh(local -> from(local.getDocument(new IdRef(expected.id()))));
            assertEquals(expected, stored);
            verified.add(stored.csv());
        }
        publish(".csv", verified);
        List<String> combined = new ArrayList<>(verified);
        combined.addAll(Files.readAllLines(await(peer, ".csv")));
        assertEquals(CREATES * 2, combined.size());
        Set<String> ids = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (String row : combined) {
            String[] fields = row.split(",");
            assertEquals(3, fields.length);
            assertEquals(fields[1], fields[2]);
            assertTrue("Duplicate UUID: " + fields[0], ids.add(fields[0]));
            assertTrue("Duplicate USN: " + fields[1], numbers.add(fields[1]));
        }
        assertEquals(Long.valueOf(CREATES * 2L), store.getLong("usn:seq:" + DAY));
        barrier(".verified");
    }
}
