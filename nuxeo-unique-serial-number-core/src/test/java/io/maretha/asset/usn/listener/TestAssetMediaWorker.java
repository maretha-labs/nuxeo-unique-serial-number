/*
 * (C) Copyright 2026 Maretha.io
 * Licensed under the Apache License, Version 2.0.
 */
package io.maretha.asset.usn.listener;

import static io.maretha.asset.usn.AssetUsnConstants.DOCTYPE_ASSET;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_FILE_CONTENT;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_PICTURE_VIEWS;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_VIDEO_TRANSCODED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Serializable;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.inject.Inject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/** Runs the stock Nuxeo imaging and video converters on real input using the add-on's async listeners. */
@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("org.nuxeo.ecm.core.mimetype")
@Deploy("org.nuxeo.ecm.platform.commandline.executor")
@Deploy("org.nuxeo.ecm.platform.tag:OSGI-INF/faceted-tag-service-core-types.xml")
@Deploy("org.nuxeo.ecm.platform.picture.core")
@Deploy("org.nuxeo.ecm.platform.video")
@Deploy("io.maretha.asset.usn.core")
@Deploy("io.maretha.asset.usn.core:OSGI-INF/media-test-path.xml")
public class TestAssetMediaWorker {
    @Inject
    protected CoreSession session;

    @Inject
    protected CoreFeature core;

    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void generatesPictureViewsForCurrentImage() throws Exception {
        DocumentModel doc = create("image", imageSource());
        core.waitForAsyncCompletion();
        doc = session.getDocument(doc.getRef());
        assertEquals(23L, ((Number) doc.getPropertyValue("picture:info/width")).longValue());
        assertFalse(((List<?>) doc.getPropertyValue(PROP_PICTURE_VIEWS)).isEmpty());
    }

    @Test
    @Deploy("org.nuxeo.ecm.actions")
    @Deploy("org.nuxeo.binary.metadata")
    public void refreshesExifFromReplacementAndKeepsCatalogDescription() throws Exception {
        File first = files.newFile("old.jpg");
        File second = files.newFile("new.jpg");
        BufferedImage pixels = new BufferedImage(23, 17, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(pixels, "jpeg", first);
        ImageIO.write(pixels, "jpeg", second);
        assertEquals(0, new ProcessBuilder("exiftool", "-overwrite_original", "-EXIF:ISO=200", "-EXIF:FocalLength=35",
                first.toString()).start().waitFor());
        assertEquals(0, new ProcessBuilder("exiftool", "-overwrite_original", "-EXIF:ISO=200", "-IPTC:Caption-Abstract=New caption",
                second.toString()).start().waitFor());
        DocumentModel doc = create("embedded-exif", Blobs.createBlob(first, "image/jpeg"));
        assertEquals("200", doc.getPropertyValue("imd:iso_speed_ratings"));
        assertFalse(((String) doc.getPropertyValue("imd:focalLength")).isBlank());
        doc.setPropertyValue("dc:description", "Catalog caption");
        doc = session.saveDocument(doc);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) Blobs.createBlob(second, "image/jpeg"));
        doc = session.saveDocument(doc);
        assertEquals("Catalog caption", doc.getPropertyValue("dc:description"));
        assertEquals("200", doc.getPropertyValue("imd:iso_speed_ratings"));
        assertNull(doc.getPropertyValue("imd:focalLength"));
        doc.addFacet("Video");
        doc = session.saveDocument(doc);
        assertFalse(doc.hasFacet("Video"));
        assertEquals("200", doc.getPropertyValue("imd:iso_speed_ratings"));
        doc = AssetMediaFacetListener.repair(session, doc);
        assertEquals("200", doc.getPropertyValue("imd:iso_speed_ratings"));
        assertEquals("Catalog caption", doc.getPropertyValue("dc:description"));
    }

    protected Blob imageSource() throws Exception {
        BufferedImage image = new BufferedImage(23, 17, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        Blob blob = Blobs.createBlob(bytes.toByteArray(), "image/png");
        blob.setFilename("picture.png");
        return blob;
    }

    @Test
    public void generatesVideoPreviewsAndConfiguredTranscodes() throws Exception {
        DocumentModel doc = create("video", videoSource());
        core.waitForAsyncCompletion();
        core.waitForAsyncCompletion(); // transcodes scheduled by the preview worker
        doc = session.getDocument(doc.getRef());
        assertEquals(64L, ((Number) doc.getPropertyValue("vid:info/width")).longValue());
        assertFalse(((List<?>) doc.getPropertyValue(PROP_PICTURE_VIEWS)).isEmpty());
        assertEquals(2, ((List<?>) doc.getPropertyValue(PROP_VIDEO_TRANSCODED)).size());
    }

    protected Blob videoSource() throws Exception {
        File video = files.newFile("source.mp4");
        Process generator = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i",
                "color=red:s=64x32:r=10", "-t", "0.5", "-pix_fmt", "yuv420p", video.getAbsolutePath())
                .inheritIO().start();
        assertEquals(0, generator.waitFor());
        return Blobs.createBlob(video, "video/mp4");
    }

    protected DocumentModel create(String name, Blob source) {
        DocumentModel doc = session.createDocumentModel("/", name, DOCTYPE_ASSET);
        doc.setPropertyValue(PROP_FILE_CONTENT, (Serializable) source);
        return session.createDocument(doc);
    }
}
