package io.maretha.asset.usn.search;

import java.util.ArrayList;
import java.util.List;

import org.nuxeo.ecm.core.search.SearchIndexDescriptor;
import org.nuxeo.ecm.core.search.index.IndexingJsonWriter;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentStartOrders;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.model.Descriptor;

/** Decorates the writers selected by the installed search clients before SearchService builds them. */
public class AssetSearchContribution extends DefaultComponent {
    static final String TARGET = "org.nuxeo.ecm.core.search";
    static final String POINT = "searchIndex";
    private final List<Descriptor> contributions = new ArrayList<>();

    @Override
    public int getApplicationStartedOrder() {
        return ComponentStartOrders.SEARCH - 1;
    }

    @Override
    public void start(ComponentContext context) {
        for (SearchIndexDescriptor index : getRegistry().<SearchIndexDescriptor>getDescriptors(TARGET, POINT)) {
            if (index.isEnabled()) {
                // A normal merge produces a plain descriptor and loses its writer factory.
                // Mask the original contribution chain for this start, then expose the decorated descriptor.
                registerOverride(new Descriptor() {
                    @Override
                    public String getId() {
                        return index.getId();
                    }

                    @Override
                    public boolean doesRemove() {
                        return true;
                    }
                });
                registerOverride(new FilteredIndex(index));
            }
        }
    }

    private void registerOverride(Descriptor descriptor) {
        getRegistry().register(TARGET, POINT, descriptor);
        contributions.add(descriptor);
    }

    @Override
    public void stop(ComponentContext context) {
        for (Descriptor descriptor : contributions) {
            getRegistry().unregister(TARGET, POINT, descriptor);
        }
        contributions.clear();
    }

    private static final class FilteredIndex extends SearchIndexDescriptor {
        private final SearchIndexDescriptor original;

        FilteredIndex(SearchIndexDescriptor original) {
            this.original = original;
            name = original.getName();
            repository = original.getRepositoryName();
            client = original.getClient();
            enabled = original.isEnabled();
            isDefault = original.isDefault();
            writerClass = original.getWriterClass();
        }

        @Override
        public IndexingJsonWriter newWriterInstance() {
            IndexingJsonWriter writer = original.newWriterInstance();
            return (output, document) -> writer.writeDocument(AssetSearchJson.filter(output), document);
        }
    }
}
