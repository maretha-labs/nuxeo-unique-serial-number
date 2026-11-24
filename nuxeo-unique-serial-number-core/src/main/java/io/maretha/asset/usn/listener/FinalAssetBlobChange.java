/*
 * (C) Copyright 2026 Maretha.io
 * Licensed under the Apache License, Version 2.0.
 */
package io.maretha.asset.usn.listener;

import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_FILE_CONTENT;

import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

/** Binary metadata rules can replace the blob at priority 100 on an otherwise metadata-only edit. */
public class FinalAssetBlobChange implements EventListener {
    @Override
    public void handleEvent(Event event) {
        if (!(event.getContext() instanceof DocumentEventContext ctx)) {
            return;
        }
        DocumentModel doc = ctx.getSourceDocument();
        if (Boolean.TRUE.equals(ctx.getProperty(AssetMediaFacetListener.PENDING))) {
            AssetMediaFacetListener.finishSourceMetadata(ctx, doc);
            return;
        }
        if (doc == null || !DOCTYPE_ASSET.equals(doc.getType()) || doc.isImmutable()
                || AssetMediaProcessor.isPublishing(doc) || !AssetMediaFacetListener.isDirty(doc, PROP_FILE_CONTENT)) {
            return;
        }
        try {
            AssetMediaFacetListener.normalize(doc);
            ctx.setProperty(AssetMediaFacetListener.PENDING, true);
        } catch (RuntimeException e) {
            event.markRollBack(e.getMessage(), e);
            throw e;
        }
    }
}
