/*
 * (C) Copyright 2026 Maretha.io
 * Licensed under the Apache License, Version 2.0.
 */
package io.maretha.asset.usn.operation;

import io.maretha.asset.usn.listener.AssetMediaFacetListener;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.SecurityConstants;

@Operation(id = "Asset.RecomputeMedia", category = Constants.CAT_DOCUMENT, label = "Recompute Asset Media",
        description = "Reclassifies a live Asset and schedules its current media for regeneration.")
public class RecomputeAssetMedia {
    @Context
    protected CoreSession session;

    @OperationMethod
    public DocumentModel run(DocumentModel doc) {
        if (!"Asset".equals(doc.getType()) || doc.isImmutable()) {
            throw new NuxeoException("Media repair requires a live Asset", 400);
        }
        if (!session.hasPermission(doc.getRef(), SecurityConstants.WRITE_PROPERTIES)) {
            throw new NuxeoException("WriteProperties is required for Asset media repair", 403);
        }
        return AssetMediaFacetListener.repair(session, doc);
    }
}
