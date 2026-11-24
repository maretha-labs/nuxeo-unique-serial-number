package io.maretha.asset.usn.service;

import static io.maretha.asset.usn.AssetUsnConstants.CONF_KV_STORE_NAME;
import static io.maretha.asset.usn.AssetUsnConstants.CONF_MAX_SUFFIX;
import static io.maretha.asset.usn.AssetUsnConstants.KV_RES_PREFIX;
import static io.maretha.asset.usn.AssetUsnConstants.KV_SEQ_PREFIX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

import com.google.inject.Inject;

@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.asset.usn.tests:OSGI-INF/two-node-allocator.xml")
@WithFrameworkProperty(name = "usn.test.kvUrl", value = "jdbc:h2:mem:usn-allocator-tests;DB_CLOSE_DELAY=-1")
public class TestPlatformUsnAllocator {

    @Inject protected KeyValueService stores;

    @Test
    @WithFrameworkProperty(name = "maretha.asset.usn.generation", value = "old-deployment")
    public void existingInstallationContinuesFromLastLeasedOrdinal() {
        KeyValueStore existing = stores.getKeyValueStore("usnStore");
        String key = KV_SEQ_PREFIX + "21000103";
        existing.put(key, 57L);
        LocalDate day = LocalDate.of(2100, 1, 3);
        assertEquals("21000103100057", new UsnServiceImpl().allocateDirect(day).getUsnString());
        assertEquals("21000103100058", new UsnServiceImpl().allocateDirect(day).getUsnString());
        assertNull(stores.getKeyValueStore("default").getLong(key));
    }

    @Test
    @WithFrameworkProperty(name = "maretha.asset.usn.generation", value = "old-deployment")
    @WithFrameworkProperty(name = CONF_KV_STORE_NAME, value = "previousCustomName")
    public void explicitExistingStoreOverridesTheOldImplicitStore() {
        String key = KV_SEQ_PREFIX + "21000104";
        stores.getKeyValueStore("previousCustomName").put(key, 98L);
        assertEquals("21000104100098", new UsnServiceImpl().allocateDirect(LocalDate.of(2100, 1, 4)).getUsnString());
        assertNull(stores.getKeyValueStore("usnStore").getLong(key));
    }

    @Test
    @WithFrameworkProperty(name = CONF_MAX_SUFFIX, value = "100001")
    public void suffixLimitIsStableForTheLifetimeOfTheAllocator() {
        UsnService service = new UsnServiceImpl();
        LocalDate day = LocalDate.of(2100, 1, 5);
        assertEquals("21000105100000", service.allocateDirect(day).getUsnString());
        Framework.getProperties().setProperty(CONF_MAX_SUFFIX, "150000");
        assertEquals("21000105100001", service.allocateDirect(day).getUsnString());
        assertEquals(409, assertThrows(UsnAllocationException.class, () -> service.allocateDirect(day)).getStatusCode());
    }

    @Test
    public void onlyOneClientCanConsumeTheReservationAcrossServiceInstances() throws Exception {
        UsnService issuer = new UsnServiceImpl();
        LocalDate day = LocalDate.of(2100, 1, 6);
        String principal = "example";
        UsnReservation receipt = issuer.reserveUsn(day, principal, null);
        KeyValueStore store = stores.getKeyValueStore("default");
        assertTrue(store.getString(KV_RES_PREFIX + receipt.getUsnString()).contains("expiresAt"));
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Boolean> claim = () -> {
            UsnService consumer = new UsnServiceImpl();
            start.await(15, TimeUnit.SECONDS);
            try {
                consumer.consumeReservation(receipt.getUsnString(), receipt.getUsnLong(), day, principal, null);
                return true;
            } catch (UsnAllocationException rejected) {
                assertEquals(409, rejected.getStatusCode());
                return false;
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(claim);
            Future<Boolean> second = workers.submit(claim);
            assertTrue(first.get(30, TimeUnit.SECONDS) ^ second.get(30, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
        assertNull(store.getString(KV_RES_PREFIX + receipt.getUsnString()));
    }
}
