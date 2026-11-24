/*
 * (C) Copyright 2026 Maretha.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.maretha.asset.usn;

import static io.maretha.asset.usn.AssetUsnConstants.CONF_MAX_SUFFIX;
import static io.maretha.asset.usn.AssetUsnConstants.CONF_MIN_SUFFIX;
import static io.maretha.asset.usn.AssetUsnConstants.DEFAULT_KV_STORE_NAME;
import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.KV_RES_PREFIX;
import static io.maretha.asset.usn.AssetUsnConstants.KV_SEQ_PREFIX;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_GENERATE_USN;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_DATE;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_LONG;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_STRING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.inject.Inject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.impl.DocumentModelImpl;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.kv.KeyValueStoreProvider;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.maretha.asset.usn.operation.ReserveUsnOperation;
import io.maretha.asset.usn.service.UsnAllocationException;
import io.maretha.asset.usn.service.UsnReservation;
import io.maretha.asset.usn.service.UsnService;
import io.maretha.asset.usn.service.UsnServiceImpl;

@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.asset.usn.core")
public class TestUsnServiceAndLifecycle {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    protected CoreSession session;

    @Inject
    protected AutomationService automationService;

    @Inject
    protected UsnService usnService;

    @Inject
    protected KeyValueService kvService;

    @Before
    public void clearKvStore() {
        KeyValueStore store = kvService.getKeyValueStore(DEFAULT_KV_STORE_NAME);
        if (store instanceof KeyValueStoreProvider provider) {
            try (var keys = provider.keyStream("usn:")) {
                keys.toList().forEach(key -> store.put(key, (String) null));
            }
        }
        Framework.getProperties().remove(CONF_MIN_SUFFIX);
        Framework.getProperties().remove(CONF_MAX_SUFFIX);
    }

    @After
    public void cleanupProps() {
        Framework.getProperties().remove(CONF_MIN_SUFFIX);
        Framework.getProperties().remove(CONF_MAX_SUFFIX);
    }

    @Test
    public void testReserveOperationPastPresentFutureDatesAndReturnOnCreate() throws Exception {
        for (String dateStr : List.of("1999-12-31", "2026-11-24", "2035-06-15")) {
            Map<String, Object> reserved = invokeReserveOperation(dateStr, null);
            String usnString = (String) reserved.get("usn_string");
            long usnLong = ((Number) reserved.get("usn_long")).longValue();
            assertEquals(dateStr, reserved.get("usn_date"));
            assertTrue("Expected 14-digit USN starting with date",
                    usnString.startsWith(dateStr.replace("-", "")) && usnString.length() == 14);
            assertEquals(usnString, Long.toString(usnLong));

            DocumentModel doc = session.createDocumentModel("/", "asset-" + dateStr, DOCTYPE_ASSET);
            doc.setPropertyValue("dc:title", "Asset " + dateStr);
            doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.parse(dateStr)));
            doc.setPropertyValue(PROP_USN_STRING, usnString);
            doc.setPropertyValue(PROP_USN_LONG, usnLong);

            // Caller receives USN immediately on creation
            DocumentModel created = session.createDocument(doc);
            assertEquals(usnString, created.getPropertyValue(PROP_USN_STRING));
            assertEquals(Long.valueOf(usnLong), created.getPropertyValue(PROP_USN_LONG));
            assertEquals(LocalDate.parse(dateStr),
                    usnService.toUtcLocalDate((java.util.Calendar) created.getPropertyValue(PROP_USN_DATE)));

            // Ephemeral reservation is atomically deleted on commit
            KeyValueStore store = kvService.getKeyValueStore(DEFAULT_KV_STORE_NAME);
            assertNull("Reservation key must be deleted on save", store.getString(KV_RES_PREFIX + usnString));
        }
    }

    @Test
    public void testOptionalCreateThenOneTimeEditAssignmentAndStrictImmutability() throws Exception {
        // 1. Create Asset without USN
        DocumentModel doc = session.createDocumentModel("/", "no-usn-asset", DOCTYPE_ASSET);
        doc.setPropertyValue("dc:title", "No USN Yet");
        doc = session.createDocument(doc);
        session.save();
        assertNull(doc.getPropertyValue(PROP_USN_STRING));
        assertNull(doc.getPropertyValue(PROP_USN_LONG));

        // 2. Normal metadata edit without USN still works
        doc.setPropertyValue("dc:description", "Updated description");
        doc = session.saveDocument(doc);
        session.save();
        assertNull(doc.getPropertyValue(PROP_USN_STRING));

        // 3. One-time USN assignment on Edit (bound to Nuxeo UUID ecm:id)
        Map<String, Object> reserved = invokeReserveOperation("2026-11-24", doc.getId());
        String usnString = (String) reserved.get("usn_string");
        long usnLong = ((Number) reserved.get("usn_long")).longValue();

        doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2026, 11, 24)));
        doc.setPropertyValue(PROP_USN_STRING, usnString);
        doc.setPropertyValue(PROP_USN_LONG, usnLong);
        doc = session.saveDocument(doc);
        session.save();

        assertEquals(usnString, doc.getPropertyValue(PROP_USN_STRING));
        assertEquals(Long.valueOf(usnLong), doc.getPropertyValue(PROP_USN_LONG));

        // 4. Subsequent normal edits (title/description) keep working when USN is untouched
        doc.setPropertyValue("dc:title", "Renamed After USN");
        doc = session.saveDocument(doc);
        session.save();
        assertEquals(usnString, doc.getPropertyValue(PROP_USN_STRING));

        finishTransaction();

        // 5. Reserving another USN for an Asset that already has one is rejected
        try {
            invokeReserveOperation("2026-11-24", doc.getId());
            fail("Expected failure when reserving USN for document that already has one");
        } catch (Exception e) {
            assertCauseIsUsnAllocationException(e);
            finishTransaction();
        }

        doc = session.getDocument(doc.getRef());
        // 6. Attempting to modify usn_string is rejected
        doc.setPropertyValue(PROP_USN_STRING, "20261124100999");
        try {
            session.saveDocument(doc);
            fail("Expected immutability failure on usn_string edit");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        }

        // 7. Attempting to remove (clear) USN is rejected
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue(PROP_USN_STRING, null);
        doc.setPropertyValue(PROP_USN_LONG, null);
        try {
            session.saveDocument(doc);
            fail("Expected immutability failure on USN removal");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        }

        // 8. Attempting to change usn_date after USN assignment is rejected
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2025, 1, 1)));
        try {
            session.saveDocument(doc);
            fail("Expected immutability failure on usn_date edit");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        }

        // 9. Persisted document still retains exact original USN values
        DocumentModel persisted = session.getDocument(doc.getRef());
        assertEquals(usnString, persisted.getPropertyValue(PROP_USN_STRING));
        assertEquals(Long.valueOf(usnLong), persisted.getPropertyValue(PROP_USN_LONG));
        assertEquals(LocalDate.of(2026, 11, 24),
                usnService.toUtcLocalDate((java.util.Calendar) persisted.getPropertyValue(PROP_USN_DATE)));
    }

    @Test
    public void testReplaySpoofingWrongUserWrongDateAndEditLock() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        String principal = session.getPrincipal().getName();
        UsnReservation r1 = usnService.reserveUsn(date, principal, null);

        // First create consumes reservation
        DocumentModel d1 = session.createDocumentModel("/", "first", DOCTYPE_ASSET);
        d1.setPropertyValue("dc:title", "First");
        d1.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
        d1.setPropertyValue(PROP_USN_STRING, r1.getUsnString());
        d1.setPropertyValue(PROP_USN_LONG, r1.getUsnLong());
        session.createDocument(d1);

        finishTransaction();

        // Replay same reserved USN fails
        DocumentModel d2 = session.createDocumentModel("/", "replay", DOCTYPE_ASSET);
        d2.setPropertyValue("dc:title", "Replay");
        d2.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
        d2.setPropertyValue(PROP_USN_STRING, r1.getUsnString());
        d2.setPropertyValue(PROP_USN_LONG, r1.getUsnLong());
        try {
            session.createDocument(d2);
            fail("Expected replay rejection");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        }

        // Forged (unreserved) USN fails
        DocumentModel forged = session.createDocumentModel("/", "forged", DOCTYPE_ASSET);
        forged.setPropertyValue("dc:title", "Forged");
        forged.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
        forged.setPropertyValue(PROP_USN_STRING, "20261124149999");
        forged.setPropertyValue(PROP_USN_LONG, 20261124149999L);
        try {
            session.createDocument(forged);
            fail("Expected forged USN rejection");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        }

        // Wrong user fails
        UsnReservation otherUserRes = usnService.reserveUsn(date, "otherUser", null);
        DocumentModel wrongUser = session.createDocumentModel("/", "wrong-user", DOCTYPE_ASSET);
        wrongUser.setPropertyValue("dc:title", "Wrong User");
        wrongUser.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
        wrongUser.setPropertyValue(PROP_USN_STRING, otherUserRes.getUsnString());
        wrongUser.setPropertyValue(PROP_USN_LONG, otherUserRes.getUsnLong());
        try {
            session.createDocument(wrongUser);
            fail("Expected wrong-user rejection");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        }

        // Mismatched usn_date fails
        UsnReservation rDate = usnService.reserveUsn(date, principal, null);
        DocumentModel wrongDate = session.createDocumentModel("/", "wrong-date", DOCTYPE_ASSET);
        wrongDate.setPropertyValue("dc:title", "Wrong Date");
        wrongDate.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2026, 11, 25)));
        wrongDate.setPropertyValue(PROP_USN_STRING, rDate.getUsnString());
        wrongDate.setPropertyValue(PROP_USN_LONG, rDate.getUsnLong());
        try {
            session.createDocument(wrongDate);
            fail("Expected mismatched-date rejection");
        } catch (UsnAllocationException expected) {
            assertEquals(422, expected.getStatusCode());
            finishTransaction();
        }

        // Concurrent edit lock keyed by Nuxeo UUID rejects competing Edit save
        DocumentModel unnumbered = session.createDocumentModel("/", "race-doc", DOCTYPE_ASSET);
        unnumbered.setPropertyValue("dc:title", "Race Doc");
        unnumbered = session.createDocument(unnumbered);
        session.save();

        UsnReservation editRes = usnService.reserveUsn(date, principal, unnumbered.getId());
        assertTrue(usnService.acquireEditLock(unnumbered.getId(), "other-in-flight-usn"));
        try {
            unnumbered.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
            unnumbered.setPropertyValue(PROP_USN_STRING, editRes.getUsnString());
            unnumbered.setPropertyValue(PROP_USN_LONG, editRes.getUsnLong());
            session.saveDocument(unnumbered);
            fail("Expected concurrent edit lock conflict");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        } finally {
            usnService.releaseEditLock(unnumbered.getId(), "other-in-flight-usn");
        }
    }

    @Test
    public void testCopyClearsUsnFields() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        UsnReservation res = usnService.reserveUsn(date, session.getPrincipal().getName(), null);

        DocumentModel folder = session.createDocumentModel("/", "dest-folder", "Folder");
        folder = session.createDocument(folder);

        DocumentModel original = session.createDocumentModel("/", "orig-asset", DOCTYPE_ASSET);
        original.setPropertyValue("dc:title", "Original Asset");
        original.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
        original.setPropertyValue(PROP_USN_STRING, res.getUsnString());
        original.setPropertyValue(PROP_USN_LONG, res.getUsnLong());
        original = session.createDocument(original);
        session.save();

        DocumentModel copy = session.copy(original.getRef(), new PathRef(folder.getPathAsString()), "copied-asset");
        session.save();

        assertNotEquals(original.getId(), copy.getId());
        assertEquals(res.getUsnString(), session.getDocument(original.getRef()).getPropertyValue(PROP_USN_STRING));
        assertNull("Copied Asset must not inherit usn_string", copy.getPropertyValue(PROP_USN_STRING));
        assertNull("Copied Asset must not inherit usn_long", copy.getPropertyValue(PROP_USN_LONG));
        assertNull("Copied Asset must not inherit usn_date", copy.getPropertyValue(PROP_USN_DATE));
    }

    @Test
    public void testBulkImportGenerateUsnReturnsUniqueUsnsWithZeroReservationKeys() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        Set<String> strings = ConcurrentHashMap.newKeySet();
        Set<Long> longs = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < 25; i++) {
            DocumentModel doc = session.createDocumentModel("/", "bulk-" + i, DOCTYPE_ASSET);
            doc.setPropertyValue("dc:title", "Bulk " + i);
            doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
            doc.setPropertyValue(PROP_GENERATE_USN, Boolean.TRUE);
            DocumentModel created = session.createDocument(doc);

            String s = (String) created.getPropertyValue(PROP_USN_STRING);
            Long l = (Long) created.getPropertyValue(PROP_USN_LONG);
            assertNotNull("USN string must be returned on create", s);
            assertNotNull("USN long must be returned on create", l);
            assertEquals(s, Long.toString(l));
            assertFalse("Transient generate_usn flag must be cleared",
                    Boolean.TRUE.equals(created.getPropertyValue(PROP_GENERATE_USN)));
            assertTrue(strings.add(s));
            assertTrue(longs.add(l));
        }

        assertEquals(25, strings.size());
        KeyValueStoreProvider provider = (KeyValueStoreProvider) kvService.getKeyValueStore(DEFAULT_KV_STORE_NAME);
        List<String> remainingKeys = provider.keyStream(KV_SEQ_PREFIX).collect(Collectors.toList());
        assertFalse(provider.keyStream(KV_RES_PREFIX).findAny().isPresent());
        assertEquals("Only the single date sequence key should remain in KV store",
                Collections.singletonList(KV_SEQ_PREFIX + "20261124"), remainingKeys);
    }

    @Test
    public void testConfigurableCapacityCapDoesNotWrapAround() {
        Framework.getProperties().setProperty(CONF_MIN_SUFFIX, "100000");
        Framework.getProperties().setProperty(CONF_MAX_SUFFIX, "100002");

        UsnService allocator = new UsnServiceImpl();
        LocalDate date = LocalDate.of(2026, 11, 24);
        assertEquals("20261124100000", allocator.allocateDirect(date).getUsnString());
        assertEquals("20261124100001", allocator.allocateDirect(date).getUsnString());
        assertEquals("20261124100002", allocator.allocateDirect(date).getUsnString());

        try {
            allocator.allocateDirect(date);
            fail("Expected capacity exhaustion error without wrap-around");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            finishTransaction();
        }
    }

    @Test
    public void testHighConcurrency5000ReservationsAndCommitsHaveZeroDuplicatesAndBoundedKvStore() throws Exception {
        int total = 5_000;
        int threads = 32;
        LocalDate date = LocalDate.of(2026, 11, 24);
        Set<String> seenStrings = ConcurrentHashMap.newKeySet();
        Set<Long> seenLongs = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            final String user = "user-" + (i % 16);
            futures.add(pool.submit(() -> {
                UsnReservation res = usnService.reserveUsn(date, user, null);
                assertTrue("Duplicate usn_string detected: " + res.getUsnString(),
                        seenStrings.add(res.getUsnString()));
                assertTrue("Duplicate usn_long detected: " + res.getUsnLong(), seenLongs.add(res.getUsnLong()));
                assertEquals(res.getUsnString(), Long.toString(res.getUsnLong()));
                assertTrue(res.getUsnString().matches("^20261124(1[0-4]\\d{4}|150000)$"));
                usnService.consumeReservation(res.getUsnString(), res.getUsnLong(), date, user, null);
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(total, seenStrings.size());
        assertEquals(total, seenLongs.size());

        // The in-memory fixture must retain one date counter and zero reservation rows.
        KeyValueStoreProvider provider = (KeyValueStoreProvider) kvService.getKeyValueStore(DEFAULT_KV_STORE_NAME);
        List<String> keys = provider.keyStream(KV_SEQ_PREFIX).collect(Collectors.toList());
        assertFalse(provider.keyStream(KV_RES_PREFIX).findAny().isPresent());
        assertEquals("Only usn:seq:20261124 should remain after all 5,000 reservations are consumed",
                Collections.singletonList(KV_SEQ_PREFIX + "20261124"), keys);
    }

    @Test
    public void testDateOnlyRemainsUnnumberedUntilExplicitGenerationForTheSavedDate() {
        LocalDate date = LocalDate.of(2030, 1, 8);
        DocumentModel dated = session.createDocumentModel("/", "dated-on-create", DOCTYPE_ASSET);
        dated.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
        dated = session.createDocument(dated);
        assertNull(dated.getPropertyValue(PROP_USN_STRING));
        assertNull(dated.getPropertyValue(PROP_USN_LONG));
        finishTransaction();
        DocumentModel edit = session.getDocument(dated.getRef());
        edit.setPropertyValue("dc:title", "date-only metadata edit");
        edit = session.saveDocument(edit);
        assertNull(edit.getPropertyValue(PROP_USN_STRING));
        edit.setPropertyValue(PROP_GENERATE_USN, true);
        edit = session.saveDocument(edit);
        String id = edit.getId();
        assertFalse(usnService.acquireEditLock(id, "competing"));
        finishTransaction();
        assertTrue(usnService.acquireEditLock(id, "after-completion"));
        usnService.releaseEditLock(id, "after-completion");
        assertEquals(edit.getPropertyValue(PROP_USN_STRING),
                session.getDocument(new IdRef(id)).getPropertyValue(PROP_USN_LONG).toString());
    }

    @Test
    public void testRollbackReleasesEditLockAndKeepsWorkingCopyUnassigned() {
        DocumentModel doc = session.createDocument(session.createDocumentModel("/", "rollback-edit", DOCTYPE_ASSET));
        finishTransaction();
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2030, 1, 9)));
        doc.setPropertyValue(PROP_GENERATE_USN, true);
        doc = session.saveDocument(doc);
        session.save();
        assertNotNull(doc.getPropertyValue(PROP_USN_STRING));
        TransactionHelper.setTransactionRollbackOnly();
        finishTransaction();
        DocumentModel persisted = session.getDocument(doc.getRef());
        assertNull(persisted.getPropertyValue(PROP_USN_STRING));
        assertNull(persisted.getPropertyValue(PROP_USN_LONG));
        assertTrue(usnService.acquireEditLock(doc.getId(), "after-rollback"));
        usnService.releaseEditLock(doc.getId(), "after-rollback");
    }

    @Test
    public void testUnexpectedInlineValidationFailureMarksTransactionForRollback() {
        DocumentModel doc = session.createDocumentModel("/", "missing-previous", DOCTYPE_ASSET);
        doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2030, 1, 10)));
        DocumentEventContext context = new DocumentEventContext(session, session.getPrincipal(), doc);
        Event beforeUpdate = context.newEvent(DocumentEventTypes.BEFORE_DOC_UPDATE);
        try {
            Framework.getService(EventService.class).fireEvent(beforeUpdate);
            fail("Unexpected listener failures must abort the write");
        } catch (IllegalStateException expected) {
            assertTrue(beforeUpdate.isMarkedForRollBack());
            assertTrue(TransactionHelper.isTransactionMarkedRollback());
            finishTransaction();
        }
    }

    @Test
    public void nativeImportCanConsumeTheTargetsReservationOnce() {
        String id = UUID.randomUUID().toString();
        LocalDate date = LocalDate.of(2030, 1, 9);
        UsnReservation reserved = usnService.reserveUsn(date, session.getPrincipal().getName(), id);
        DocumentModel input = session.createDocumentModel("/", "reserved-import", DOCTYPE_ASSET);
        ((DocumentModelImpl) input).setId(id);
        input.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
        input.setPropertyValue(PROP_USN_STRING, reserved.getUsnString());
        input.setPropertyValue(PROP_USN_LONG, reserved.getUsnLong());
        session.importDocuments(List.of(input));
        finishTransaction();
        assertEquals(reserved.getUsnLong(), session.getDocument(new IdRef(id)).getPropertyValue(PROP_USN_LONG));
        assertThrows(UsnAllocationException.class, () -> usnService.consumeImportReservation(
                reserved.getUsnString(), reserved.getUsnLong(), date, session.getPrincipal().getName(), id));
    }

    @Test
    public void testNativeImportRejectsUnadmittedLiteralAndAllocatesDate() {
        DocumentModel input = session.createDocumentModel("/", "imported-asset", DOCTYPE_ASSET);
        ((DocumentModelImpl) input).setId(UUID.randomUUID().toString());
        input.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2030, 1, 10)));
        input.setPropertyValue(PROP_USN_STRING, "20300110122222");
        input.setPropertyValue(PROP_USN_LONG, 20300110122222L);
        try {
            session.importDocuments(List.of(input));
            fail("Unadmitted native import");
        } catch (UsnAllocationException expected) {
            assertEquals(409, expected.getStatusCode());
            assertTrue(TransactionHelper.isTransactionMarkedRollback());
            finishTransaction();
        }
        assertFalse(session.exists(new IdRef(input.getId())));
        input.setPropertyValue(PROP_USN_STRING, null);
        input.setPropertyValue(PROP_USN_LONG, null);
        input.setPropertyValue(PROP_GENERATE_USN, true);
        session.importDocuments(List.of(input));
        finishTransaction();
        DocumentModel imported = session.getDocument(new IdRef(input.getId()));
        assertNotNull(imported.getPropertyValue(PROP_USN_STRING));
        assertEquals(imported.getPropertyValue(PROP_USN_STRING), imported.getPropertyValue(PROP_USN_LONG).toString());
    }

    @Test
    public void testVersionRestorePreservesLiveIdentityAndRejectsDifferentSeries() {
        DocumentModel doc = session.createDocument(session.createDocumentModel("/", "versioned-asset", DOCTYPE_ASSET));
        DocumentRef oldVersion = session.checkIn(doc.getRef(), VersioningOption.MAJOR, "Before assignment");
        session.checkOut(doc.getRef());
        doc = session.getDocument(doc.getRef());
        doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2030, 1, 11)));
        doc.setPropertyValue(PROP_GENERATE_USN, true);
        doc = session.saveDocument(doc);
        String assigned = (String) doc.getPropertyValue(PROP_USN_STRING);
        finishTransaction();

        session.restoreToVersion(doc.getRef(), oldVersion);
        finishTransaction();
        assertEquals(assigned, session.getDocument(doc.getRef()).getPropertyValue(PROP_USN_STRING));
        assertNull(session.getDocument(oldVersion).getPropertyValue(PROP_USN_STRING));

        DocumentModel other = session.createDocument(session.createDocumentModel("/", "second-series", DOCTYPE_ASSET));
        try {
            session.restoreToVersion(other.getRef(), oldVersion);
            fail("Restoring another series would change document ownership");
        } catch (UsnAllocationException expected) {
            assertTrue(TransactionHelper.isTransactionMarkedRollback());
            finishTransaction();
        }
    }

    @Test
    public void testNestedFolderCopyClearsDescendantIdentities() {
        DocumentModel folder = session.createDocument(session.createDocumentModel("/", "copy-source", "Folder"));
        DocumentModel nested = session.createDocument(session.createDocumentModel(folder.getPathAsString(), "nested", "Folder"));
        DocumentModel asset = session.createDocumentModel(nested.getPathAsString(), "numbered", DOCTYPE_ASSET);
        asset.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2030, 1, 12)));
        asset.setPropertyValue(PROP_GENERATE_USN, true);
        asset = session.createDocument(asset);
        String assigned = (String) asset.getPropertyValue(PROP_USN_STRING);
        finishTransaction();
        DocumentModel copy = session.copy(folder.getRef(), session.getRootDocument().getRef(), "copy-destination");
        finishTransaction();
        DocumentModel copied = session.getDocument(new PathRef(copy.getPathAsString() + "/nested/numbered"));
        assertNull(copied.getPropertyValue(PROP_USN_STRING));
        assertNull(copied.getPropertyValue(PROP_USN_LONG));
        assertNull(copied.getPropertyValue(PROP_USN_DATE));
        assertEquals(assigned, session.getDocument(asset.getRef()).getPropertyValue(PROP_USN_STRING));
    }

    @Test
    public void testAssignedValuesComparedExactly() {
        DocumentModel doc = session.createDocumentModel("/", "exact-identity", DOCTYPE_ASSET);
        doc.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(LocalDate.of(2030, 1, 13)));
        doc.setPropertyValue(PROP_GENERATE_USN, true);
        doc = session.createDocument(doc);
        finishTransaction();
        String assigned = (String) doc.getPropertyValue(PROP_USN_STRING);
        doc.setPropertyValue(PROP_USN_STRING, " " + assigned);
        try {
            session.saveDocument(doc);
            fail("Writing a noncanonical representation");
        } catch (UsnAllocationException expected) {
            assertTrue(TransactionHelper.isTransactionMarkedRollback());
            finishTransaction();
        }
        doc = session.getDocument(doc.getRef());
        Calendar anotherTime = (Calendar) ((Calendar) doc.getPropertyValue(PROP_USN_DATE)).clone();
        anotherTime.add(Calendar.MINUTE, 1);
        doc.setPropertyValue(PROP_USN_DATE, anotherTime);
        try {
            session.saveDocument(doc);
            fail("Changing the stored date without changing its day");
        } catch (UsnAllocationException expected) {
            assertTrue(TransactionHelper.isTransactionMarkedRollback());
            finishTransaction();
        }
    }

    @Test
    public void testVerifiedHistoricalSingleFieldRepairIsIdempotent() throws Exception {
        var admin = Framework.getService(org.nuxeo.ecm.core.event.EventServiceAdmin.class);
        LocalDate date = LocalDate.of(2030, 1, 14);
        for (boolean stringOnly : List.of(true, false)) {
            var assigned = usnService.allocateDirect(date);
            DocumentModel historical = session.createDocumentModel("/", "legacy-" + stringOnly, DOCTYPE_ASSET);
            historical.setPropertyValue(PROP_USN_DATE, usnService.toUtcNoonCalendar(date));
            if (stringOnly) {
                historical.setPropertyValue(PROP_USN_STRING, assigned.getUsnString());
            } else {
                historical.setPropertyValue(PROP_USN_LONG, assigned.getUsnLong());
            }
            // Model a row written before the dual-field guard was deployed.
            admin.setListenerEnabledFlag("usnValidationListener", false);
            try {
                historical = session.createDocument(historical);
            } finally {
                admin.setListenerEnabledFlag("usnValidationListener", true);
            }
            finishTransaction();
            for (int attempt = 0; attempt < 2; attempt++) {
                try (OperationContext ctx = new OperationContext(session)) {
                    ctx.setInput(session.getDocument(historical.getRef()));
                    automationService.run(ctx, "Asset.RepairUsnPair", Map.of("expectedUsn", assigned.getUsnString()));
                }
                finishTransaction();
            }
            DocumentModel fixed = session.getDocument(historical.getRef());
            assertEquals(assigned.getUsnString(), fixed.getPropertyValue(PROP_USN_STRING));
            assertEquals(assigned.getUsnLong(), fixed.getPropertyValue(PROP_USN_LONG));
        }
    }

    @Test
    public void testCompactDatesAndReceiptExpiry() throws Exception {
        assertEquals(LocalDate.of(2024, 2, 29), usnService.parseUsnDate("20240229"));
        org.junit.Assert.assertThrows(UsnAllocationException.class, () -> usnService.parseUsnDate("20230229"));
        org.junit.Assert.assertThrows(UsnAllocationException.class, () -> usnService.parseUsnDate("20260431"));

        LocalDate date = LocalDate.of(2030, 1, 17);
        String user = session.getPrincipal().getName();
        String doc = java.util.UUID.randomUUID().toString();
        UsnReservation newlyReserved = usnService.reserveUsn(date, user, null);
        org.junit.Assert.assertThrows(UsnAllocationException.class, () -> usnService.consumeReservation(
                newlyReserved.getUsnString(), newlyReserved.getUsnLong(), date, user, doc));
        usnService.consumeReservation(newlyReserved.getUsnString(), newlyReserved.getUsnLong(), date, user, null);

        UsnReservation old = usnService.allocateDirect(date);
        String key = KV_RES_PREFIX + old.getUsnString();
        KeyValueStore store = kvService.getKeyValueStore(DEFAULT_KV_STORE_NAME);
        store.put(key, "{\"user\":\"" + user + "\",\"usnDate\":\"" + date + "\"}", 600);
        org.junit.Assert.assertThrows(UsnAllocationException.class, () -> usnService.consumeImportReservation(
                old.getUsnString(), old.getUsnLong(), date, user, doc, session.getRepositoryName()));
        assertThrows(UsnAllocationException.class, () -> usnService.consumeReservation(
                old.getUsnString(), old.getUsnLong(), date, user, null));

        UsnReservation reservation = usnService.reserveUsn(date, user, null);
        String reservationKey = KV_RES_PREFIX + reservation.getUsnString();
        var receipt = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(store.getString(reservationKey));
        receipt.put("expiresAt", java.time.Instant.EPOCH.toString());
        store.put(reservationKey, receipt.toString(), 600);
        assertThrows(UsnAllocationException.class, () -> usnService.consumeReservation(
                reservation.getUsnString(), reservation.getUsnLong(), date, user, null));
    }

    private static void finishTransaction() {
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
    }

    private Map<String, Object> invokeReserveOperation(String usnDate, String docId) throws Exception {
        try (OperationContext ctx = new OperationContext(session)) {
            Map<String, Object> params = new HashMap<>();
            params.put("usnDate", usnDate);
            if (docId != null) {
                params.put("docId", docId);
            }
            Blob result = (Blob) automationService.run(ctx, ReserveUsnOperation.ID, params);
            return MAPPER.readValue(result.getString(), new TypeReference<>() {
            });
        }
    }

    private void assertCauseIsUsnAllocationException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof UsnAllocationException) {
                return;
            }
            cur = cur.getCause();
        }
        fail("Expected UsnAllocationException in cause chain, got: " + t);
    }
}
