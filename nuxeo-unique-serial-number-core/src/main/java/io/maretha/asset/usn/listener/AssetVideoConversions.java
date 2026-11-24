/*
 * (C) Copyright 2026 Maretha.io
 * Licensed under the Apache License, Version 2.0.
 */
package io.maretha.asset.usn.listener;

import java.util.List;

import org.nuxeo.ecm.platform.video.service.AutomaticVideoConversion;
import org.nuxeo.ecm.platform.video.service.AutomaticVideoConversionContributionHandler;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentName;
import org.nuxeo.runtime.model.DefaultComponent;

/** Reads the configured automatic conversions once per runtime start, including LTS 2023 without a VideoService getter. */
public class AssetVideoConversions extends DefaultComponent {

    private static final ComponentName VIDEO = new ComponentName("org.nuxeo.ecm.platform.video.service.VideoService");
    private List<String> names = List.of();

    @Override
    public void start(ComponentContext context) {
        var manager = Framework.getRuntime().getComponentManager();
        var configured = new AutomaticVideoConversionContributionHandler();
        for (var name : manager.getResolvedRegistrations()) {
            for (var ext : manager.getRegistrationInfo(name).getExtensions()) {
                if (!VIDEO.equals(ext.getTargetComponent()) || !"automaticVideoConversions".equals(ext.getExtensionPoint())
                        || ext.getContributions() == null) {
                    continue;
                }
                for (Object value : ext.getContributions()) {
                    if (value instanceof AutomaticVideoConversion conversion) {
                        configured.addContribution(conversion);
                    }
                }
            }
        }
        names = configured.registry.values().stream().sorted().map(AutomaticVideoConversion::getName).toList();
    }

    public List<String> names() {
        return names;
    }
}
