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

import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_AUDIO;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_HAS_STORYBOARD;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_HAS_VIDEO_PREVIEW;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_PICTURE;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_VIDEO;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_FILE_CONTENT;
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_USN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import io.maretha.asset.usn.listener.AssetMediaFacetListener;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.impl.DocumentModelImpl;

import com.google.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.schema.DocumentType;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy("org.nuxeo.ecm.core.mimetype")
@Deploy("org.nuxeo.ecm.platform.commandline.executor")
@Deploy("org.nuxeo.ecm.platform.tag:OSGI-INF/faceted-tag-service-core-types.xml")
@Deploy("org.nuxeo.ecm.platform.picture.core:OSGI-INF/picture-schemas-contrib.xml")
@Deploy("org.nuxeo.ecm.platform.video:OSGI-INF/core-types-contrib.xml")
@Deploy("org.nuxeo.ecm.platform.audio.core:OSGI-INF/core-types-contrib.xml")
@Deploy("org.nuxeo.ecm.platform.thumbnail:OSGI-INF/thumbnail-core-types-contrib.xml")
@Deploy("io.maretha.asset.usn.core")
@Deploy("io.maretha.asset.usn.core:OSGI-INF/media-schema-tests.xml")
public class TestAssetMediaFacetListener {

    @Inject
    protected CoreSession session;


    @Inject
    protected SchemaManager schemaManager;

    @Test
    public void testAssetDoctypeExtendsFileAndHasUsnSchema() {
        DocumentType assetType = schemaManager.getDocumentType(DOCTYPE_ASSET);
        assertTrue(assetType.getSuperType() != null && "File".equals(assetType.getSuperType().getName()));
        assertTrue(assetType.hasSchema(SCHEMA_USN));
        assertTrue(schemaManager.getAllowedSubTypes("Workspace").contains(DOCTYPE_ASSET));
        assertTrue(schemaManager.getAllowedSubTypes("Folder").contains(DOCTYPE_ASSET));
        assertTrue(schemaManager.getAllowedSubTypes("OrderedFolder").contains(DOCTYPE_ASSET));
    }

    @Test
    public void testDynamicFacetLifecycleAcrossMediaTypes() {
        // 1. Create Asset with an image -> Picture facet
        DocumentModel doc = session.createDocumentModel("/", "my-asset", DOCTYPE_ASSET);
        doc.setPropertyValue("dc:title", "My Asset");
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("photo.png", "image/png"));
        doc = session.createDocument(doc);
        session.save();

        assertTrue("Expected Picture facet on image Asset", doc.hasFacet(FACET_PICTURE));
        assertFalse(doc.hasFacet(FACET_VIDEO));
        assertFalse(doc.hasFacet(FACET_AUDIO));

        // 2. Replace file:content with a video -> Picture removed, Video + storyboard/preview added
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("clip.mp4", "video/mp4"));
        doc = session.saveDocument(doc);
        session.save();

        assertFalse("Picture facet should be removed when replaced by video", doc.hasFacet(FACET_PICTURE));
        assertTrue("Expected Video facet", doc.hasFacet(FACET_VIDEO));
        assertTrue("Expected HasStoryboard facet", doc.hasFacet(FACET_HAS_STORYBOARD));
        assertTrue("Expected HasVideoPreview facet", doc.hasFacet(FACET_HAS_VIDEO_PREVIEW));
        assertFalse(doc.hasFacet(FACET_AUDIO));

        // 3. Replace file:content with audio -> Video facets removed, Audio added
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("track.mp3", "audio/mpeg"));
        doc = session.saveDocument(doc);
        session.save();

        assertFalse(doc.hasFacet(FACET_PICTURE));
        assertFalse(doc.hasFacet(FACET_VIDEO));
        assertFalse(doc.hasFacet(FACET_HAS_STORYBOARD));
        assertFalse(doc.hasFacet(FACET_HAS_VIDEO_PREVIEW));
        assertTrue("Expected Audio facet", doc.hasFacet(FACET_AUDIO));

        // 4. Replace file:content with PDF -> all media facets removed
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("report.pdf", "application/pdf"));
        doc = session.saveDocument(doc);
        session.save();

        assertFalse(doc.hasFacet(FACET_PICTURE));
        assertFalse(doc.hasFacet(FACET_VIDEO));
        assertFalse(doc.hasFacet(FACET_AUDIO));

        // 5. Set back to image, then clear blob to null -> facet removed
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("second.jpg", "image/jpeg"));
        doc = session.saveDocument(doc);
        assertTrue(doc.hasFacet(FACET_PICTURE));

        doc.setPropertyValue(PROP_FILE_CONTENT, null);
        doc = session.saveDocument(doc);
        assertFalse(doc.hasFacet(FACET_PICTURE));
    }

    @Test
    public void testFallbackMimeDetectionFromFilename() {
        DocumentModel doc = session.createDocumentModel("/", "octet-image", DOCTYPE_ASSET);
        doc.setPropertyValue("dc:title", "Octet Image");
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("sample.png", "application/octet-stream"));
        doc = session.createDocument(doc);

        assertTrue("Expected Picture facet detected from .png filename fallback", doc.hasFacet(FACET_PICTURE));
    }

    @Test
    public void testCheckedInVersionRetainsOriginalFacetAfterLiveUpdate() {
        DocumentModel doc = session.createDocumentModel("/", "versioned-asset", DOCTYPE_ASSET);
        doc.setPropertyValue("dc:title", "Versioned Asset");
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("v1.png", "image/png"));
        doc = session.createDocument(doc);
        DocumentRef v1Ref = doc.checkIn(VersioningOption.MAJOR, "Initial image version");
        session.save();

        // Update live doc to audio
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("v2.mp3", "audio/mpeg"));
        doc = session.saveDocument(doc);
        session.save();

        DocumentModel v1 = session.getDocument(v1Ref);
        assertTrue("Version 1.0 should retain Picture facet", v1.hasFacet(FACET_PICTURE));
        assertFalse(v1.hasFacet(FACET_AUDIO));
        assertTrue("Live document should have Audio facet", doc.hasFacet(FACET_AUDIO));
        assertFalse(doc.hasFacet(FACET_PICTURE));
    }

    @Test
    public void testFacetOnlySaveReconcilesWithCurrentBlob() {
        DocumentModel doc = session.createDocumentModel("/", "facet-only", DOCTYPE_ASSET);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("picture.jpg", "image/jpeg"));
        doc = session.createDocument(doc);
        String first = (String) doc.getPropertyValue(AssetMediaFacetListener.GENERATION);
        doc.removeFacet(FACET_PICTURE);
        doc.addFacet(FACET_VIDEO);
        doc = session.saveDocument(doc);
        assertTrue(doc.hasFacet(FACET_PICTURE));
        assertFalse(doc.hasFacet(FACET_VIDEO));
        assertNotEquals(first, doc.getPropertyValue(AssetMediaFacetListener.GENERATION));
    }

    @Test
    public void testNativeImportClassifiesAndStoresCanonicalMime() {
        String id = UUID.randomUUID().toString();
        DocumentModel doc = session.createDocumentModel("/", "import-media", DOCTYPE_ASSET);
        ((DocumentModelImpl) doc).setId(id);
        doc.setPropertyValue("dc:title", "Imported");
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("sample.png", "application/octet-stream"));
        session.importDocuments(List.of(doc));
        DocumentModel stored = session.getDocument(new IdRef(id));
        assertTrue(stored.hasFacet(FACET_PICTURE));
        assertEquals("image/png", ((Blob) stored.getPropertyValue(PROP_FILE_CONTENT)).getMimeType());
    }

    @Test
    public void testDistinctGenerationForAbaAndStableAcrossMetadataSave() {
        DocumentModel doc = session.createDocumentModel("/", "aba", DOCTYPE_ASSET);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("first.jpg", "image/jpeg"));
        doc = session.createDocument(doc);
        String first = (String) doc.getPropertyValue(AssetMediaFacetListener.GENERATION);
        doc.setPropertyValue("dc:title", "New title");
        doc = session.saveDocument(doc);
        assertEquals(first, doc.getPropertyValue(AssetMediaFacetListener.GENERATION));
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("other.mp4", "video/mp4"));
        doc = session.saveDocument(doc);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("first.jpg", "image/jpeg"));
        doc = session.saveDocument(doc);
        assertNotEquals(first, doc.getPropertyValue(AssetMediaFacetListener.GENERATION));
    }

    @Test
    public void replacingMediaClearsSourceMetadataAndPreservesDescriptiveFields() {
        DocumentModel doc = session.createDocumentModel("/", "embedded-metadata", DOCTYPE_ASSET);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("first.jpg", "image/jpeg"));
        doc = session.createDocument(doc);
        doc.setPropertyValue("imd:equipment", "Camera A");
        doc.setPropertyValue("dc:description", "Catalog description");
        doc = session.saveDocument(doc);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("second.png", "image/png"));
        doc = session.saveDocument(doc);
        assertNull(doc.getPropertyValue("imd:equipment"));
        assertEquals("Catalog description", doc.getPropertyValue("dc:description"));

        doc.setPropertyValue("imd:equipment", "Camera B");
        doc = session.saveDocument(doc);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("first.mp3", "audio/mpeg"));
        doc = session.saveDocument(doc);
        doc.setPropertyValue("aud:duration", 100L);
        doc = session.saveDocument(doc);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("second.wav", "audio/wav"));
        doc = session.saveDocument(doc);
        assertNull(doc.getPropertyValue("aud:duration"));
        doc.setPropertyValue("aud:duration", 300L);
        doc = session.saveDocument(doc);
        doc.setPropertyValue(PROP_FILE_CONTENT, null);
        doc = session.saveDocument(doc);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("third.mp3", "audio/mpeg"));
        doc = session.saveDocument(doc);
        assertNull(doc.getPropertyValue("aud:duration"));

        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) blob("third.jpg", "image/jpeg"));
        doc = session.saveDocument(doc);
        assertNull(doc.getPropertyValue("imd:equipment"));
        assertEquals("Catalog description", doc.getPropertyValue("dc:description"));
    }

    private Blob blob(String filename, String mimeType) {
        Blob b = Blobs.createBlob("dummy-content", mimeType);
        b.setFilename(filename);
        return b;
    }
}
