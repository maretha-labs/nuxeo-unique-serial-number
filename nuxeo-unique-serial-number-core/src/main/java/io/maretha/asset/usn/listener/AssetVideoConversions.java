/*
 * (C) Copyright 2026 Maretha.io
 * Licensed under the Apache License, Version 2.0.
 */
package io.maretha.asset.usn.listener;

import java.util.List;
import org.nuxeo.ecm.platform.video.service.VideoService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.DefaultComponent;

/** Uses the public automatic conversion registry on LTS 2025. */
public class AssetVideoConversions extends DefaultComponent {
    public List<String> names() {
        return Framework.getService(VideoService.class).getAutomaticVideoConversionsNames();
    }
}
