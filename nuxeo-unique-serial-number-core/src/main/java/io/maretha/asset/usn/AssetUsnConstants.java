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

/**
 * Shared constants for the Asset document type, USN schema, dynamic media facets, and configuration properties.
 */
public final class AssetUsnConstants {

    public static final String DOCTYPE_ASSET = "Asset";

    public static final String SCHEMA_USN = "usn";

    public static final String SCHEMA_FILE = "file";

    public static final String SCHEMA_PICTURE = "picture";

    public static final String SCHEMA_IMAGE_METADATA = "image_metadata";

    public static final String SCHEMA_VIDEO = "video";

    public static final String SCHEMA_AUDIO = "audio";

    public static final String PROP_USN_DATE = "usn:usn_date";

    public static final String PROP_USN_STRING = "usn:usn_string";

    public static final String PROP_USN_LONG = "usn:usn_long";

    public static final String PROP_GENERATE_USN = "usn:generate_usn";

    public static final String PROP_FILE_CONTENT = "file:content";

    // Nuxeo media facets (matching ImagingDocumentConstants, VideoConstants, Audio & Thumbnail core XML)
    public static final String FACET_PICTURE = "Picture";

    public static final String FACET_VIDEO = "Video";

    public static final String FACET_HAS_STORYBOARD = "HasStoryboard";

    public static final String FACET_HAS_VIDEO_PREVIEW = "HasVideoPreview";

    public static final String FACET_AUDIO = "Audio";

    public static final String FACET_THUMBNAIL = "Thumbnail";

    // Nuxeo media properties cleared on facet transitions
    public static final String PROP_PICTURE_VIEWS = "picture:views";

    public static final String PROP_PICTURE_INFO = "picture:info";

    public static final String PROP_VIDEO_INFO = "vid:info";

    public static final String PROP_VIDEO_STORYBOARD = "vid:storyboard";

    public static final String PROP_VIDEO_TRANSCODED = "vid:transcodedVideos";

    public static final String PROP_AUDIO_DURATION = "aud:duration";

    public static final String PROP_THUMBNAIL = "thumb:thumbnail";

    // Nuxeo media listener context flags (verified from ImagingDocumentConstants & VideoConstants)
    public static final String CTX_FORCE_VIEWS_GENERATION = "forceViewsGeneration";

    public static final String CTX_FORCE_INFORMATIONS_GENERATION = "forceInformationsGeneration";

    /** Retained for binary compatibility with older consumers. The listener ignores this flag. */
    @Deprecated
    public static final String CTX_ALLOW_USN_COPY_CLEAR = "io.maretha.asset.usn.allowCopyClear";

    // KeyValueStore configuration and key prefixes
    public static final String DEFAULT_KV_STORE_NAME = "default";

    public static final String KV_SEQ_PREFIX = "usn:seq:";

    public static final String KV_RES_PREFIX = "usn:res:";

    public static final String KV_LOCK_PREFIX = "usn:lock:";

    // nuxeo.conf property keys and defaults
    public static final String CONF_KV_STORE_NAME = "maretha.asset.usn.kvStoreName";

    public static final String CONF_MIN_SUFFIX = "maretha.asset.usn.minSuffix";

    public static final String CONF_MAX_SUFFIX = "maretha.asset.usn.maxSuffix";

    public static final String CONF_RESERVATION_TTL_SECONDS = "maretha.asset.usn.reservationTtlSeconds";

    public static final String CONF_EDIT_LOCK_TTL_SECONDS = "maretha.asset.usn.editLockTtlSeconds";

    public static final long DEFAULT_MIN_SUFFIX = 100_000L;

    public static final long DEFAULT_MAX_SUFFIX = 150_000L;

    public static final long DEFAULT_RESERVATION_TTL_SECONDS = 86_400L; // 24 hours

    public static final long DEFAULT_EDIT_LOCK_TTL_SECONDS = 60L;

    public static final int MIN_USN_YEAR = 1000;

    public static final int MAX_USN_YEAR = 9999;

    private AssetUsnConstants() {
        // Constants class
    }
}
