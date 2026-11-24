package io.maretha.asset.usn.listener;

import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_USN;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.runtime.api.Framework;

import io.maretha.asset.usn.service.UsnAllocationException;
import io.maretha.asset.usn.service.UsnService;

@Operation(id = "Asset.RepairUsnPair", category = Constants.CAT_DOCUMENT, label = "Repair Asset USN Pair",
        description = "Completes a verified historical single-field USN using its existing number and date.")
public class RepairUsnPair {
    private static final ThreadLocal<DocumentModel> REPAIRING = new ThreadLocal<>();

    @Context protected CoreSession session;
    @Param(name = "expectedUsn", description = "Exact canonical USN from the reviewed inventory.")
    protected String expectedUsn;

    static boolean isRepair(DocumentModel doc) {
        return REPAIRING.get() == doc;
    }

    @OperationMethod
    public DocumentModel run(DocumentModel input) {
        if (!session.getPrincipal().isAdministrator()) {
            throw new UsnAllocationException("USN repair requires an administrator", 403);
        }
        DocumentModel doc = session.getDocument(input.getRef());
        if (doc.isImmutable() || !doc.hasSchema(SCHEMA_USN)) {
            throw new UsnAllocationException("Select a live USN document for repair", 422);
        }
        UsnFields original = UsnFields.read(doc);
        UsnFields complete = original.completed();
        if (!complete.text().equals(expectedUsn)) {
            throw new UsnAllocationException("Stored USN differs from the reviewed inventory", 409);
        }
        UsnService service = Framework.getService(UsnService.class);
        service.validateFormatAndBounds(complete.text(), complete.number(), service.toUtcLocalDate(complete.date()));
        if (original.matches(complete)) {
            return doc;
        }
        doc.putContextData(CoreSession.CHANGE_TOKEN, doc.getChangeToken());
        doc.putContextData(CoreSession.USER_CHANGE, Boolean.TRUE);
        complete.write(doc);
        REPAIRING.set(doc);
        try {
            return session.saveDocument(doc);
        } finally {
            REPAIRING.remove();
        }
    }
}
