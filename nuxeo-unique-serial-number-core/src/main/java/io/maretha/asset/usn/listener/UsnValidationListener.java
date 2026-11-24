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
package io.maretha.asset.usn.listener;

import static io.maretha.asset.usn.AssetUsnConstants.PROP_GENERATE_USN;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_DATE;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_LONG;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_STRING;
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_USN;
import static io.maretha.asset.usn.service.UsnAllocationException.SC_CONFLICT;
import static io.maretha.asset.usn.service.UsnAllocationException.SC_UNPROCESSABLE_ENTITY;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.ABOUT_TO_CREATE;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.ABOUT_TO_IMPORT;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.BEFORE_DOC_RESTORE;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.BEFORE_DOC_UPDATE;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.DOCUMENT_RESTORED;

import java.time.LocalDate;
import java.util.UUID;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.facet.VersioningDocument;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

import io.maretha.asset.usn.service.UsnAllocationException;
import io.maretha.asset.usn.service.UsnReservation;
import io.maretha.asset.usn.service.UsnService;

/** Assigns explicitly requested identities and preserves the live owner's values during restore. */
public class UsnValidationListener implements EventListener {

    private static final String RESTORE_FIELDS = UsnValidationListener.class.getName() + ".restoreFields";

    @Override
    public void handleEvent(Event event) {
        if (!(event.getContext() instanceof DocumentEventContext ctx)) {
            return;
        }
        try {
            DocumentModel doc = ctx.getSourceDocument();
            if (doc == null || !doc.hasSchema(SCHEMA_USN)) {
                return;
            }
            switch (event.getName()) {
                case ABOUT_TO_CREATE -> assign(ctx, doc, UsnFields.read(doc), null, false);
                case ABOUT_TO_IMPORT -> handleImport(ctx, doc);
                case BEFORE_DOC_UPDATE -> update(ctx, doc);
                case BEFORE_DOC_RESTORE -> captureLiveIdentity(ctx, doc);
                case DOCUMENT_RESTORED -> restoreLiveIdentity(ctx, doc);
                default -> { }
            }
        } catch (RuntimeException failure) {
            event.markRollBack(failure.getMessage(), failure);
            // The dispatcher rethrows ConcurrentUpdateException before consulting an event's rollback flag.
            TransactionHelper.setTransactionRollbackOnly();
            throw failure;
        }
    }

    private static void captureLiveIdentity(DocumentEventContext ctx, DocumentModel doc) {
        String versionId = (String) ctx.getProperty(VersioningDocument.RESTORED_VERSION_UUID_KEY);
        DocumentModel version = ctx.getCoreSession().getDocument(new IdRef(versionId));
        if (!version.isVersion() || !doc.getId().equals(version.getVersionSeriesId())) {
            throw new UsnAllocationException("An Asset can only be restored from its own version series", SC_CONFLICT);
        }
        ctx.setProperty(RESTORE_FIELDS, UsnFields.read(doc));
    }

    private static void restoreLiveIdentity(DocumentEventContext ctx, DocumentModel doc) {
        if (!(ctx.getProperty(RESTORE_FIELDS) instanceof UsnFields live)) {
            throw new IllegalStateException("Missing live Asset identity during restore");
        }
        if (!live.matches(UsnFields.read(doc))) {
            live.write(doc);
        }
        clearGenerateFlag(doc);
        doc.putContextData(CoreSession.USER_CHANGE, Boolean.TRUE);
    }

    private static void handleImport(DocumentEventContext ctx, DocumentModel doc) {
        UsnFields fields = UsnFields.read(doc);
        if (Boolean.TRUE.equals(ctx.getProperty(CoreSession.IMPORT_IS_VERSION))) {
            if (Boolean.TRUE.equals(doc.getPropertyValue(PROP_GENERATE_USN))) {
                throw new UsnAllocationException("Cannot allocate an identity to a historical version", SC_CONFLICT);
            }
            if (fields.numbered()) {
                String ownerId = (String) ctx.getProperty(CoreSession.IMPORT_VERSION_VERSIONABLE_ID);
                if (ownerId == null) {
                    throw new UsnAllocationException("Missing version-series owner for imported USN", SC_CONFLICT);
                }
                DocumentModel owner = ctx.getCoreSession().getDocument(new IdRef(ownerId));
                if (owner.isProxy() || owner.isVersion() || !owner.hasSchema(SCHEMA_USN)
                        || !fields.matches(UsnFields.read(owner))) {
                    throw new UsnAllocationException("Imported version identity differs from its live owner", SC_CONFLICT);
                }
            }
            return;
        }
        // Native import provides a stable document UUID. Supplied numbers require a reservation bound to that UUID.
        assign(ctx, doc, fields, doc.getId(), true);
    }

    private static void update(DocumentEventContext ctx, DocumentModel doc) {
        if (!doc.getProperty(PROP_USN_DATE).isDirty() && !doc.getProperty(PROP_USN_STRING).isDirty()
                && !doc.getProperty(PROP_USN_LONG).isDirty() && !doc.getProperty(PROP_GENERATE_USN).isDirty()) {
            return;
        }
        if (UsnCopyListener.isClearingCopy(doc)) {
            return;
        }
        if (!(ctx.getProperty(CoreEventConstants.PREVIOUS_DOCUMENT_MODEL) instanceof DocumentModel previous)) {
            throw new IllegalStateException("Missing previous Asset during USN validation");
        }
        UsnFields original = UsnFields.read(previous);
        UsnFields submitted = UsnFields.read(doc);
        if (original.numbered() || doc.isProxy() || doc.isVersion()) {
            if (RepairUsnPair.isRepair(doc) && original.numbered() && original.completed().matches(submitted)) {
                return;
            }
            if (!original.matches(submitted)) {
                throw new UsnAllocationException("An assigned USN and its date are immutable", SC_CONFLICT);
            }
            clearGenerateFlag(doc);
            return;
        }
        if (!submitted.numbered() && submitted.date() == null) {
            if (Boolean.TRUE.equals(doc.getPropertyValue(PROP_GENERATE_USN))) {
                throw new UsnAllocationException("A USN date is required", SC_UNPROCESSABLE_ENTITY);
            }
            return;
        }
        if (!submitted.numbered() && !Boolean.TRUE.equals(doc.getPropertyValue(PROP_GENERATE_USN))) {
            if (!original.matches(submitted)) {
                normalizeDateOnly(doc, submitted);
            }
            return;
        }
        UsnService service = Framework.getService(UsnService.class);
        String id = doc.getId();
        String token = UUID.randomUUID().toString();
        if (!service.acquireEditLock(id, token)) {
            throw new UsnAllocationException("A USN assignment is already in progress for " + id, SC_CONFLICT);
        }
        try {
            TransactionCompletion.after(() -> service.releaseEditLock(id, token));
        } catch (RuntimeException failure) {
            service.releaseEditLock(id, token);
            throw failure;
        }
        if (doc.getContextData(CoreSession.CHANGE_TOKEN) == null) {
            String expected = doc.getChangeToken();
            if (expected == null) {
                throw new IllegalStateException("A change token is required when numbering a saved Asset");
            }
            doc.putContextData(CoreSession.CHANGE_TOKEN, expected);
        }
        doc.putContextData(CoreSession.USER_CHANGE, Boolean.TRUE);
        assign(ctx, doc, submitted, id, false);
    }

    private static void assign(DocumentEventContext ctx, DocumentModel doc, UsnFields fields, String expectedDocId,
            boolean nativeImport) {
        boolean requested = Boolean.TRUE.equals(doc.getPropertyValue(PROP_GENERATE_USN));
        clearGenerateFlag(doc);
        if (fields.date() == null) {
            if (requested || fields.numbered()) {
                throw new UsnAllocationException("A USN date is required", SC_UNPROCESSABLE_ENTITY);
            }
            return;
        }
        if (!requested && !fields.numbered()) {
            normalizeDateOnly(doc, fields);
            return;
        }
        UsnService service = Framework.getService(UsnService.class);
        LocalDate date = service.toUtcLocalDate(fields.date());
        String text;
        Long number;
        if (fields.numbered()) {
            // Older clients may send a single representation. Verify its sibling and normalize only after checking the reservation.
            UsnFields completed = fields.completed();
            text = completed.text();
            number = completed.number();
            String username = ctx.getPrincipal().getName();
            if (nativeImport) {
                service.consumeImportReservation(text, number, date, username, expectedDocId,
                        ctx.getCoreSession().getRepositoryName());
            } else {
                service.consumeReservation(text, number, date, username, expectedDocId);
            }
        } else {
            UsnReservation assigned = service.allocateDirect(date);
            text = assigned.getUsnString();
            number = assigned.getUsnLong();
        }
        new UsnFields(text, number, service.toUtcNoonCalendar(date)).write(doc);
    }

    private static void normalizeDateOnly(DocumentModel doc, UsnFields fields) {
        if (fields.date() != null) {
            UsnService service = Framework.getService(UsnService.class);
            doc.setPropertyValue(PROP_USN_DATE, service.toUtcNoonCalendar(service.toUtcLocalDate(fields.date())));
        }
    }

    private static void clearGenerateFlag(DocumentModel doc) {
        if (Boolean.TRUE.equals(doc.getPropertyValue(PROP_GENERATE_USN))) {
            doc.setPropertyValue(PROP_GENERATE_USN, Boolean.FALSE);
        }
    }
}
