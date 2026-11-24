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

import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.platform.mimetype.interfaces.MimetypeRegistry;
import org.nuxeo.runtime.api.Framework;

/**
 * Classifies a {@link Blob} into a target media category ({@link MediaKind}) based on its MIME type, falling back to
 * Nuxeo's {@link MimetypeRegistry} when the MIME type is missing or generic ({@code application/octet-stream}).
 */
public final class MediaFacetClassifier {

    public enum MediaKind {
        PICTURE, VIDEO, AUDIO, OTHER
    }

    private static final String OCTET_STREAM = "application/octet-stream";

    private static final Set<String> EXTRA_IMAGE_MIME_TYPES = Set.of("application/photoshop",
            "application/illustrator", "application/postscript");

    private static final Set<String> EXTRA_VIDEO_MIME_TYPES = Set.of("application/mxf", "application/gxf");

    private MediaFacetClassifier() {
        // Utility class
    }

    public static MediaKind classify(Blob blob) {
        if (blob == null) {
            return MediaKind.OTHER;
        }
        String mimeType = resolveMimeType(blob);
        if (StringUtils.isBlank(mimeType)) {
            return MediaKind.OTHER;
        }
        String normalized = stripParameters(mimeType).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/") || EXTRA_IMAGE_MIME_TYPES.contains(normalized)) {
            return MediaKind.PICTURE;
        }
        if (normalized.startsWith("video/") || EXTRA_VIDEO_MIME_TYPES.contains(normalized)) {
            return MediaKind.VIDEO;
        }
        if (normalized.startsWith("audio/")) {
            return MediaKind.AUDIO;
        }
        return MediaKind.OTHER;
    }

    static String resolveMimeType(Blob blob) {
        String mimeType = blob.getMimeType();
        if (StringUtils.isNotBlank(mimeType) && !OCTET_STREAM.equalsIgnoreCase(stripParameters(mimeType))) {
            MimetypeRegistry registry = Framework.getService(MimetypeRegistry.class);
            String bare = stripParameters(mimeType).toLowerCase(Locale.ROOT);
            String canonical = registry == null ? bare : registry.getNormalizedMimeType(bare).orElse(bare);
            if (!canonical.equals(mimeType)) {
                blob.setMimeType(canonical);
            }
            return canonical;
        }
        MimetypeRegistry registry = Framework.getService(MimetypeRegistry.class);
        if (registry == null) {
            return mimeType;
        }
        String detected = registry.getMimetypeFromFilenameAndBlobWithDefault(blob.getFilename(), blob, mimeType);
        if (StringUtils.isNotBlank(detected)) {
            blob.setMimeType(detected);
            return detected;
        }
        return mimeType;
    }

    private static String stripParameters(String mimeType) {
        int semi = mimeType.indexOf(';');
        return (semi >= 0 ? mimeType.substring(0, semi) : mimeType).trim();
    }
}
