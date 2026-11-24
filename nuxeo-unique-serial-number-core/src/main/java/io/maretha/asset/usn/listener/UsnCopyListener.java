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
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_USN;
import static org.nuxeo.ecm.core.api.event.CoreEventConstants.DESTINATION_REF;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.ABOUT_TO_COPY;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.DOCUMENT_CREATED_BY_COPY;

import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.ScrollResult;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.runtime.transaction.TransactionHelper;

/** Clears cloned identity values on the copy root and live descendants before CoreSession.copy returns. */
public class UsnCopyListener implements EventListener {

    private static final String COPY_CONTEXT = UsnCopyListener.class.getName() + ".origin";

    private enum CopyOrigin { NATIVE }

    private static final ThreadLocal<DocumentModel> CLEARING = new ThreadLocal<>();

    static boolean isClearingCopy(DocumentModel doc) {
        return doc == CLEARING.get();
    }

    @Override
    public void handleEvent(Event event) {
        if (!(event.getContext() instanceof DocumentEventContext ctx)) {
            return;
        }
        if (ABOUT_TO_COPY.equals(event.getName())) {
            if (event.isInline() && ctx.getProperty(DESTINATION_REF) instanceof org.nuxeo.ecm.core.api.DocumentRef) {
                ctx.setProperty(COPY_CONTEXT, CopyOrigin.NATIVE);
            }
            return;
        }
        if (!DOCUMENT_CREATED_BY_COPY.equals(event.getName())) {
            return;
        }
        try {
            DocumentModel root = ctx.getSourceDocument();
            if (root == null || root.isProxy() || root.isVersion()) {
                return;
            }
            // AbstractSession writes the root model after this event.
            clear(root);
            // The privileged descendant pass requires the original native copy options.
            if (root.isFolder() && ctx.getProperty(COPY_CONTEXT) == CopyOrigin.NATIVE) {
                // The copy owner may lack read/write permission on nested children with inherited ACL restrictions.
                CoreInstance.doPrivileged(ctx.getCoreSession(), (CoreSession session) -> clearDescendants(session, root.getId()));
            }
        } catch (RuntimeException failure) {
            event.markRollBack(failure.getMessage(), failure);
            TransactionHelper.setTransactionRollbackOnly();
            throw failure;
        }
    }

    private static void clearDescendants(CoreSession session, String rootId) {
        String query = "SELECT ecm:uuid FROM Document WHERE ecm:ancestorId = " + NXQL.escapeString(rootId)
                + " AND ecm:isProxy = 0 AND ecm:isVersion = 0 AND (usn:usn_string IS NOT NULL"
                + " OR usn:usn_long IS NOT NULL OR usn:usn_date IS NOT NULL OR usn:generate_usn = 1)";
        ScrollResult<String> batch = session.scroll(query, 100, 60);
        while (batch.hasResults()) {
            for (String id : batch.getResults()) {
                DocumentModel doc = session.getDocument(new IdRef(id));
                if (clear(doc)) {
                    DocumentModel enclosing = CLEARING.get();
                    CLEARING.set(doc);
                    try {
                        doc.putContextData(VersioningService.DISABLE_AUTOMATIC_VERSIONING, Boolean.TRUE);
                        doc.putContextData(VersioningService.DISABLE_AUTO_CHECKOUT, Boolean.TRUE);
                        session.saveDocument(doc);
                    } finally {
                        if (enclosing == null) {
                            CLEARING.remove();
                        } else {
                            CLEARING.set(enclosing);
                        }
                    }
                }
            }
            batch = session.scroll(batch.getScrollId());
        }
    }

    private static boolean clear(DocumentModel doc) {
        if (!doc.hasSchema(SCHEMA_USN) || (UsnFields.EMPTY.matches(UsnFields.read(doc))
                && !Boolean.TRUE.equals(doc.getPropertyValue(PROP_GENERATE_USN)))) {
            return false;
        }
        UsnFields.EMPTY.write(doc);
        doc.setPropertyValue(PROP_GENERATE_USN, Boolean.FALSE);
        return true;
    }
}
