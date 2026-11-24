package io.maretha.asset.usn.search;

import org.nuxeo.runtime.ComponentEvent;
import org.nuxeo.runtime.ComponentListener;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.ComponentStartOrders;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.model.Extension;

/** Captures the writer selected by the installed search extensions, including custom writers. */
public class AssetSearchContribution extends DefaultComponent implements ComponentListener {
    static final String TARGET = "org.nuxeo.elasticsearch.ElasticSearchComponent";
    static final String POINT = "elasticSearchDocWriter";
    private Object original;

    // Nuxeo instantiates all components before activating any of their extension points.
    public AssetSearchContribution() {
        Framework.getRuntime().getComponentManager().addComponentListener(this);
    }

    @Override
    public void handleEvent(ComponentEvent event) {
        if (event.id == ComponentEvent.EXTENSION_REGISTERED && event.data instanceof Extension ext
                && TARGET.equals(ext.getTargetComponent().getName()) && POINT.equals(ext.getExtensionPoint())) {
            Object[] writers = ext.getContributions();
            if (writers != null && writers.length > 0) {
                original = writers[writers.length - 1];
            }
        }
    }

    @Override
    public int getApplicationStartedOrder() {
        return ComponentStartOrders.ELASTIC - 1;
    }

    @Override
    public void start(ComponentContext context) {
        if (original != null) {
            SearchWriter.install((DefaultComponent) Framework.getRuntime().getComponent(TARGET),
                    (ComponentInstance) context);
        }
    }

    @Override
    public void stop(ComponentContext context) {
        if (original != null) {
            DefaultComponent target = (DefaultComponent) Framework.getRuntime().getComponent(TARGET);
            target.registerContribution(original, POINT, (ComponentInstance) context);
        }
    }

    @Override
    public void deactivate(ComponentContext context) {
        Framework.getRuntime().getComponentManager().removeComponentListener(this);
        super.deactivate(context);
    }

    Object originalDescriptor() {
        return original;
    }
}
