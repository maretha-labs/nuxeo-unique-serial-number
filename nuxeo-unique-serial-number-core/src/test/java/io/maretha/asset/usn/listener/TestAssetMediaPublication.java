/*
 * (C) Copyright 2026 Maretha.io
 * Licensed under the Apache License, Version 2.0.
 */
package io.maretha.asset.usn.listener;

import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_FILE_CONTENT;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_PICTURE_INFO;
import static io.maretha.asset.usn.listener.AssetMediaFacetListener.GENERATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.google.inject.Inject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy("org.nuxeo.ecm.core.mimetype")
@Deploy("org.nuxeo.ecm.platform.tag:OSGI-INF/faceted-tag-service-core-types.xml")
@Deploy("org.nuxeo.ecm.platform.picture.core:OSGI-INF/picture-schemas-contrib.xml")
@Deploy("io.maretha.asset.usn.core")
@Deploy("io.maretha.asset.usn.core:OSGI-INF/media-schema-tests.xml")
public class TestAssetMediaPublication {
    @Inject
    protected CoreSession session;


    @Test
    public void testLateResultAndUnstampedStockWrite() {
        DocumentModel doc = session.createDocumentModel("/", "publication", DOCTYPE_ASSET);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) Blobs.createBlob("first", "image/jpeg"));
        doc = session.createDocument(doc);
        String first = (String) doc.getPropertyValue(GENERATION);
        var firstBlob = AssetMediaProcessor.Source.of((Blob) doc.getPropertyValue(PROP_FILE_CONTENT));
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) Blobs.createBlob("second", "image/jpeg"));
        doc = session.saveDocument(doc);
        String second = (String) doc.getPropertyValue(GENERATION);
        assertNotEquals(first, second);
        Map<String, Serializable> fresh = Map.of(PROP_PICTURE_INFO, new HashMap<>(Map.of("width", 99L)));
        Map<String, Serializable> stale = Map.of(PROP_PICTURE_INFO, new HashMap<>(Map.of("width", 1L)));
        var secondBlob = AssetMediaProcessor.Source.of((Blob) doc.getPropertyValue(PROP_FILE_CONTENT));
        AssetMediaProcessor.publishCurrent(session, doc.getRef(), second, secondBlob, fresh, null);
        AssetMediaProcessor.publishCurrent(session, doc.getRef(), first, firstBlob, stale, null);
        AssetMediaProcessor.publishCurrent(session, doc.getRef(), second, firstBlob, stale, null);
        DocumentModel reloaded = session.getDocument(doc.getRef());
        assertEquals(99L, ((Number) reloaded.getPropertyValue("picture:info/width")).longValue());
        reloaded.setPropertyValue(PROP_PICTURE_INFO, stale.get(PROP_PICTURE_INFO));
        assertThrows(NuxeoException.class, () -> session.saveDocument(reloaded));
    }
}
