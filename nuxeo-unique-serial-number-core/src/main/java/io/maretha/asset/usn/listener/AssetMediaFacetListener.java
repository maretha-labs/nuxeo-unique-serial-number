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

import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_AUDIO;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_HAS_STORYBOARD;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_HAS_VIDEO_PREVIEW;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_PICTURE;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_THUMBNAIL;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_VIDEO;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_FILE_CONTENT;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_AUDIO_DURATION;
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_AUDIO;
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_IMAGE_METADATA;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_PICTURE_INFO;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_PICTURE_VIEWS;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_THUMBNAIL;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_VIDEO_INFO;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_VIDEO_STORYBOARD;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_VIDEO_TRANSCODED;
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_PICTURE;
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_VIDEO;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DataModel;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.runtime.api.Framework;

import io.maretha.asset.usn.listener.MediaFacetClassifier.MediaKind;

/** Manages derived media only on live Asset documents. */
public class AssetMediaFacetListener implements EventListener {

    public static final String GENERATION = "assetmedia:generation";
    static final String PROCESS = "assetMediaGenerationRequested";
    static final String PENDING = "assetMediaGenerationChanged";
    private static final String CATALOG_VALUES = "assetMediaCatalogValues";
    private static final String MISSING_EXIF = "assetMediaMissingExif";
    private static final List<String> CONTROLLED = List.of(FACET_PICTURE, FACET_VIDEO, FACET_HAS_STORYBOARD,
            FACET_HAS_VIDEO_PREVIEW, FACET_AUDIO);
    static final List<String> DERIVED = List.of(PROP_PICTURE_VIEWS, PROP_PICTURE_INFO, PROP_VIDEO_INFO,
            PROP_VIDEO_STORYBOARD, PROP_VIDEO_TRANSCODED, PROP_THUMBNAIL);
    private static final List<String> DISABLE_STOCK = List.of("disablePictureViewsGenerationListener",
            "disableVideoConversionsGenerationListener", "disableThumbnailComputation");

    @Override
    public void handleEvent(Event event) {
        if (!(event.getContext() instanceof DocumentEventContext ctx)) {
            return;
        }
        DocumentModel doc = ctx.getSourceDocument();
        if (doc == null || !DOCTYPE_ASSET.equals(doc.getType()) || doc.isProxy() || doc.isVersion()
                || (DocumentEventTypes.ABOUT_TO_IMPORT.equals(event.getName())
                        && ctx.getProperty(CoreSession.IMPORT_VERSION_VERSIONABLE_ID) != null)) {
            return;
        }
        try {
            process(event.getName(), ctx, doc);
        } catch (RuntimeException e) {
            event.markRollBack(e.getMessage(), e);
            throw e;
        }
    }

    private void process(String name, DocumentEventContext ctx, DocumentModel doc) {
        // These switches are consumed by the built-in schedulers, including their post-commit listeners.
        DISABLE_STOCK.forEach(key -> ctx.setProperty(key, Boolean.TRUE));
        switch (name) {
            case DocumentEventTypes.DOCUMENT_CREATED, DocumentEventTypes.DOCUMENT_IMPORTED -> submit(ctx, doc);
            case DocumentEventTypes.DOCUMENT_UPDATED -> {
                if (Boolean.TRUE.equals(ctx.getProperty(PENDING))) {
                    submit(ctx, doc);
                }
            }
            case DocumentEventTypes.DOCUMENT_RESTORED, DocumentEventTypes.DOCUMENT_CREATED_BY_COPY -> {
                normalize(doc);
                submit(ctx, doc);
            }
            case DocumentEventTypes.ABOUT_TO_CREATE, DocumentEventTypes.ABOUT_TO_IMPORT -> normalize(doc);
            case DocumentEventTypes.BEFORE_DOC_UPDATE -> {
                DocumentModel previous = (DocumentModel) ctx.getProperty(CoreEventConstants.PREVIOUS_DOCUMENT_MODEL);
                if (AssetMediaProcessor.isPublishing(doc)) {
                    return;
                }
                boolean sourceChanged = isDirty(doc, PROP_FILE_CONTENT);
                if (sourceChanged || previous != null && CONTROLLED.stream().anyMatch(f -> doc.hasFacet(f) != previous.hasFacet(f))) {
                    normalize(doc);
                    // Facet-only repair must re-extract EXIF just like an explicit repair.
                    if (!sourceChanged && doc.hasSchema(SCHEMA_IMAGE_METADATA)) markSourceForExtraction(doc);
                    prepareSourceMetadata(ctx, doc, previous);
                    ctx.setProperty(PENDING, Boolean.TRUE);
                } else if (isDirty(doc, GENERATION) || DERIVED.stream().anyMatch(path -> isDirty(doc, path))) {
                    // An old stock work item may still be queued during an upgrade or explicit recomputation.
                    // Its result has no source generation, even if it rereads the document immediately before save.
                    throw new NuxeoException("Asset derivatives require a matching source generation", 409);
                }
            }
            default -> { }
        }
    }

    private static void prepareSourceMetadata(DocumentEventContext ctx, DocumentModel doc, DocumentModel previous) {
        if (doc.hasSchema(SCHEMA_AUDIO) && !doc.getProperty(PROP_AUDIO_DURATION).isDirty()) {
            clearValue(doc.getProperty(PROP_AUDIO_DURATION));
        }
        if (!doc.hasSchema(SCHEMA_IMAGE_METADATA)) return;
        // Populated catalog descriptions, rights and source survive file replacements. A missing catalog
        // value can still be populated from the new image by the native IPTC mapping.
        HashMap<String, Serializable> catalog = new HashMap<>();
        for (String name : List.of("dc:description", "dc:rights", "dc:source")) {
            Property field = doc.getProperty(name);
            if (field.isDirty() || field.getValue() != null) catalog.put(name, field.getValue());
        }
        if (!catalog.isEmpty()) ctx.setProperty(CATALOG_VALUES, catalog);
        ArrayList<String> previouslySet = new ArrayList<>();
        boolean added = previous != null && !previous.hasSchema(SCHEMA_IMAGE_METADATA);
        for (Property field : doc.getPropertyObjects(SCHEMA_IMAGE_METADATA)) {
            if (!field.isDirty() && (added || field.getValue() != null)) {
                previouslySet.add(field.getField().getName().getPrefixedName());
                field.setValue(null);
                // The stock rule selects DOC_TO_BLOB when a mapped field is dirty. Keep the replacement
                // eligible for BLOB_TO_DOC, then persist any fields absent from the new EXIF after it runs.
                field.clearDirtyFlags();
            }
        }
        if (!previouslySet.isEmpty()) ctx.setProperty(MISSING_EXIF, previouslySet);
    }

    static void finishSourceMetadata(DocumentEventContext ctx, DocumentModel doc) {
        if (ctx.getProperty(CATALOG_VALUES) instanceof Map<?, ?> catalog) {
            catalog.forEach((name, value) -> doc.setPropertyValue((String) name, (Serializable) value));
        }
        if (!(ctx.getProperty(MISSING_EXIF) instanceof List<?> fields)) return;
        for (Object name : fields) {
            Property field = doc.getProperty((String) name);
            if (field.getValue() == null) {
                clearValue(field);
            }
        }
    }

    private static void clearValue(Property field) {
        // A newly reattached facet may expose an empty DocumentPart while a VCS fragment retains old fields.
        field.setForceDirty(true);
        try {
            field.setValue(null);
        } finally {
            field.setForceDirty(false);
        }
    }

    static boolean isDirty(DocumentModel doc, String path) {
        String prefix = path.substring(0, path.indexOf(':'));
        String schema = switch (prefix) {
            case "vid" -> SCHEMA_VIDEO;
            case "thumb" -> "thumbnail";
            case "assetmedia" -> "asset_media";
            default -> prefix;
        };
        DataModel data = doc.getDataModels().get(schema);
        return data != null && data.isDirty(path.substring(path.indexOf(':') + 1));
    }

    static void normalize(DocumentModel doc) {
        Blob blob = (Blob) doc.getPropertyValue(PROP_FILE_CONTENT);
        MediaKind kind = MediaFacetClassifier.classify(blob);
        for (String facet : CONTROLLED) {
            boolean needed = switch (kind) {
                case PICTURE -> FACET_PICTURE.equals(facet);
                case VIDEO -> FACET_VIDEO.equals(facet) || FACET_HAS_STORYBOARD.equals(facet)
                        || FACET_HAS_VIDEO_PREVIEW.equals(facet);
                case AUDIO -> FACET_AUDIO.equals(facet);
                case OTHER -> false;
            };
            if (needed && !doc.hasFacet(facet)) {
                if (Framework.getService(SchemaManager.class).getFacet(facet) != null) {
                    doc.addFacet(facet);
                }
            } else if (!needed && doc.hasFacet(facet)) {
                doc.removeFacet(facet);
            }
        }
        if (doc.hasSchema(SCHEMA_PICTURE)) {
            clearValue(doc.getProperty(PROP_PICTURE_VIEWS));
            clearValue(doc.getProperty(PROP_PICTURE_INFO));
        }
        if (doc.hasSchema(SCHEMA_VIDEO)) {
            clearValue(doc.getProperty(PROP_VIDEO_INFO));
            clearValue(doc.getProperty(PROP_VIDEO_STORYBOARD));
            clearValue(doc.getProperty(PROP_VIDEO_TRANSCODED));
        }
        if (doc.hasFacet(FACET_THUMBNAIL)) {
            doc.setPropertyValue(PROP_THUMBNAIL, null);
        }
        String generation = blob == null ? null : UUID.randomUUID().toString();
        Framework.doPrivileged(() -> doc.setPropertyValue(GENERATION, generation));
        doc.putContextData(CoreSession.USER_CHANGE, true);
    }

    public static DocumentModel repair(CoreSession session, DocumentModel doc) {
        // Reload the document so the repair persists only media-related changes.
        DocumentModel fresh = session.getDocument(doc.getRef());
        fresh.putContextData(CoreSession.CHANGE_TOKEN, fresh.getChangeToken());
        if (fresh.getPropertyValue(PROP_FILE_CONTENT) != null) {
            markSourceForExtraction(fresh);
            return session.saveDocument(fresh);
        }
        normalize(fresh);
        return AssetMediaProcessor.saveMedia(session, fresh);
    }

    private static void markSourceForExtraction(DocumentModel doc) {
        // Re-run native BLOB_TO_DOC mappings on the current blob.
        Property source = doc.getProperty(PROP_FILE_CONTENT);
        source.setForceDirty(true);
        try {
            source.setValue(source.getValue());
        } finally {
            source.setForceDirty(false);
        }
    }

    private static void submit(DocumentEventContext ctx, DocumentModel doc) {
        Serializable generation = doc.getPropertyValue(GENERATION);
        if (generation != null) {
            DocumentEventContext request = new DocumentEventContext(ctx.getCoreSession(), ctx.getPrincipal(), doc);
            request.setProperty(GENERATION, generation);
            Framework.getService(EventService.class).fireEvent(request.newEvent(PROCESS));
        }
    }
}
