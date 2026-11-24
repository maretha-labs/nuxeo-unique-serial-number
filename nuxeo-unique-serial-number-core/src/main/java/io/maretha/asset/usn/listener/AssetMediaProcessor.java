/*
 * (C) Copyright 2026 Maretha.io
 * Licensed under the Apache License, Version 2.0.
 */
package io.maretha.asset.usn.listener;

import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_PICTURE;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_THUMBNAIL;
import static io.maretha.asset.usn.AssetUsnConstants.FACET_VIDEO;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_FILE_CONTENT;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_PICTURE_INFO;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_PICTURE_VIEWS;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_THUMBNAIL;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_VIDEO_INFO;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_VIDEO_STORYBOARD;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_VIDEO_TRANSCODED;
import static io.maretha.asset.usn.listener.AssetMediaFacetListener.GENERATION;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.thumbnail.ThumbnailService;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.PostCommitEventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.platform.picture.api.adapters.PictureResourceAdapter;
import org.nuxeo.ecm.platform.video.Video;
import org.nuxeo.ecm.platform.video.VideoDocument;
import org.nuxeo.ecm.platform.video.VideoHelper;
import org.nuxeo.ecm.platform.video.service.VideoService;
import org.nuxeo.ecm.platform.video.service.VideoConversionWork;
import org.nuxeo.ecm.platform.picture.PictureViewsGenerationWork;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

/** Computes on one source revision. Only fresh models in short, optimistic write transactions can publish. */
public class AssetMediaProcessor implements PostCommitEventListener {

    record Source(String provider, String key, String digest) {
        static Source of(Blob blob) {
            if (blob instanceof ManagedBlob managed) {
                return new Source(managed.getProviderId(), managed.getKey(), managed.getDigest());
            }
            return blob == null ? null : new Source(null, null, blob.getDigest());
        }
    }

    private record Output(Source source, Map<String, Serializable> values) {}

    static final String TRANSCODE = "assetVideoConversionRequested";
    private static final String CONVERSION = "conversion";
    private static final ThreadLocal<DocumentModel> PUBLISHING = new ThreadLocal<>();

    static boolean isPublishing(DocumentModel doc) {
        return doc == PUBLISHING.get();
    }

    static DocumentModel saveMedia(CoreSession session, DocumentModel doc) {
        PUBLISHING.set(doc);
        try {
            return session.saveDocument(doc);
        } finally {
            PUBLISHING.remove();
        }
    }

    @Override
    public void handleEvent(EventBundle events) {
        for (Event event : events) {
            if ((!AssetMediaFacetListener.PROCESS.equals(event.getName()) && !TRANSCODE.equals(event.getName()))
                    || !(event.getContext() instanceof DocumentEventContext ctx)
                    || !(ctx.getProperty(GENERATION) instanceof String generation)) {
                continue;
            }
            DocumentModel source = ctx.getSourceDocument();
            if (source == null) {
                continue;
            }
            String repository = source.getRepositoryName();
            DocumentRef ref = source.getRef();
            String conversion = (String) ctx.getProperty(CONVERSION);
            if (TRANSCODE.equals(event.getName())) {
                transcode(repository, ref, generation, conversion);
                continue;
            }
            int timeout = Integer.parseInt(Framework.getProperty("asset.media.previewTimeoutSeconds", "300"));
            Output result = TransactionHelper.runWithoutTransaction(() ->
                    TransactionHelper.runInTransaction(timeout, () -> {
                        CoreSession session = CoreInstance.getCoreSessionSystem(repository);
                        DocumentModel doc = current(session, ref, generation);
                        return doc == null ? null : compute(session, doc);
                    }));
            if (result != null && !result.values().isEmpty()) {
                publish(repository, ref, generation, result, null);
            }
        }
    }

    private static DocumentModel current(CoreSession session, DocumentRef ref, String generation) {
        if (!session.exists(ref)) {
            return null;
        }
        DocumentModel doc = session.getDocument(ref);
        return DOCTYPE_ASSET.equals(doc.getType()) && !doc.isVersion() && !doc.isProxy()
                && generation.equals(doc.getPropertyValue(GENERATION)) ? doc : null;
    }

    private static Output compute(CoreSession session, DocumentModel doc) {
        Blob source = (Blob) doc.getPropertyValue(PROP_FILE_CONTENT);
        Map<String, Serializable> result = new LinkedHashMap<>();
        if (source == null) {
            return null;
        }
        try {
            if (doc.hasFacet(FACET_PICTURE)) {
                PictureResourceAdapter adapter = doc.getAdapter(PictureResourceAdapter.class);
                if (adapter == null) {
                    throw new NuxeoException("Picture adapter unavailable for Asset " + doc.getId());
                }
                adapter.fillPictureViews(source, source.getFilename(), doc.getTitle(), null);
                capture(doc, result, PROP_PICTURE_VIEWS, PROP_PICTURE_INFO);
            } else if (doc.hasFacet(FACET_VIDEO)) {
                VideoHelper.updateVideoInfo(doc, source);
                VideoHelper.updateStoryboard(doc, source);
                VideoHelper.updatePreviews(doc, source);
                capture(doc, result, PROP_VIDEO_INFO, PROP_VIDEO_STORYBOARD, PROP_PICTURE_VIEWS, PROP_PICTURE_INFO);
            }
            if (doc.hasFacet(FACET_THUMBNAIL)) {
                ThumbnailService thumbnails = Framework.getService(ThumbnailService.class);
                if (thumbnails != null) {
                    result.put(PROP_THUMBNAIL, (Serializable) thumbnails.computeThumbnail(doc, session));
                }
            }
        } catch (IOException e) {
            throw new NuxeoException("Could not generate media for Asset " + doc.getId(), e);
        }
        return new Output(Source.of(source), result);
    }

    private static void capture(DocumentModel doc, Map<String, Serializable> result, String... paths) {
        for (String path : paths) {
            result.put(path, doc.getPropertyValue(path));
        }
    }

    private static void transcode(String repository, DocumentRef ref, String generation, String conversion) {
        Video source = TransactionHelper.runInNewTransaction(() -> {
            DocumentModel doc = current(CoreInstance.getCoreSessionSystem(repository), ref, generation);
            if (doc == null || !doc.hasFacet(FACET_VIDEO)) {
                return null;
            }
            VideoDocument videoDoc = doc.getAdapter(VideoDocument.class);
            return videoDoc.getTranscodedVideo(conversion) == null ? videoDoc.getVideo() : null;
        });
        if (source != null) {
            VideoService service = Framework.getService(VideoService.class);
            if (Framework.getService(AssetVideoConversions.class).names().contains(conversion)) {
                Map<String, Serializable> converted = TransactionHelper.runWithoutTransaction(
                        () -> service.convert(source, conversion).toMap());
                publish(repository, ref, generation, new Output(Source.of(source.getBlob()), converted), conversion);
            }
        }
    }

    private static void publish(String repository, DocumentRef ref, String generation, Output result,
            String conversion) {
        for (int attempt = 0; ; attempt++) {
            try {
                TransactionHelper.runInNewTransaction(() -> publishCurrent(CoreInstance.getCoreSessionSystem(repository),
                        ref, generation, result.source(), result.values(), conversion));
                return;
            } catch (ConcurrentUpdateException conflict) {
                if (attempt >= 2) {
                    throw conflict;
                }
                // Retry publication only. A superseded generation is discarded on the fresh read.
            }
        }
    }

    static void publishCurrent(CoreSession session, DocumentRef ref, String generation, Source source,
            Map<String, Serializable> result, String conversion) {
        DocumentModel dest = current(session, ref, generation);
        if (dest == null || !source.equals(Source.of((Blob) dest.getPropertyValue(PROP_FILE_CONTENT)))) {
            return;
        }
        dest.putContextData(CoreSession.CHANGE_TOKEN, dest.getChangeToken());
        dest.putContextData(CoreSession.USER_CHANGE, true);
        dest.putContextData(VersioningService.DISABLE_AUTO_CHECKOUT, true);
        dest.putContextData(VersioningService.DISABLE_AUTOMATIC_VERSIONING, true);
        dest.putContextData(CoreSession.DISABLE_AUDIT_LOGGER, true);
        dest.putContextData("disableDublinCoreListener", true);
        dest.putContextData("disableNotificationService", true);
        boolean conversionsCompleted = false;
        if (conversion == null) {
            result.forEach(dest::setPropertyValue);
        } else {
            @SuppressWarnings("unchecked")
            List<Map<String, Serializable>> existing =
                    (List<Map<String, Serializable>>) dest.getPropertyValue(PROP_VIDEO_TRANSCODED);
            ArrayList<Map<String, Serializable>> videos = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            if (videos.stream().anyMatch(v -> conversion.equals(v.get("name")))) {
                return; // Already committed by another delivery of this exact generation.
            }
            videos.add(result);
            dest.setPropertyValue(PROP_VIDEO_TRANSCODED, videos);
            List<String> configured = Framework.getService(AssetVideoConversions.class).names();
            conversionsCompleted = configured.stream().allMatch(name -> videos.stream().anyMatch(v -> name.equals(v.get("name"))));
        }
        DocumentModel saved = saveMedia(session, dest);
        if (conversion == null && saved.hasFacet(FACET_PICTURE)) {
            fire(session, saved, PictureViewsGenerationWork.PICTURE_VIEWS_GENERATION_DONE_EVENT);
        }
        if (conversionsCompleted) {
            fire(session, saved, VideoConversionWork.VIDEO_CONVERSIONS_DONE_EVENT);
        }
        if (conversion == null && saved.hasFacet(FACET_VIDEO)) {
            for (String name : Framework.getService(AssetVideoConversions.class).names()) {
                var request = new DocumentEventContext(session, session.getPrincipal(), saved);
                request.setProperty(GENERATION, generation);
                request.setProperty(CONVERSION, name);
                Framework.getService(EventService.class).fireEvent(request.newEvent(TRANSCODE));
            }
        }
    }

    private static void fire(CoreSession session, DocumentModel doc, String name) {
        var context = new DocumentEventContext(session, session.getPrincipal(), doc);
        Framework.getService(EventService.class).fireEvent(context.newEvent(name));
    }
}
