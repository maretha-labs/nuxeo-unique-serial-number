package io.maretha.asset.usn.search;

import java.io.IOException;
import java.util.Map;

import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.elasticsearch.config.ElasticSearchDocWriterDescriptor;
import org.nuxeo.elasticsearch.io.JsonESDocumentWriter;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;

import com.fasterxml.jackson.core.JsonGenerator;

/** The LTS 2023 writer extension instantiates its writer with a no-argument constructor. */
public class SearchWriter extends JsonESDocumentWriter {
    private final JsonESDocumentWriter delegate;

    public SearchWriter() throws ReflectiveOperationException {
        AssetSearchContribution contribution = (AssetSearchContribution) Framework.getRuntime()
                .getComponent("io.maretha.asset.usn.search.writer");
        ElasticSearchDocWriterDescriptor original = (ElasticSearchDocWriterDescriptor) contribution.originalDescriptor();
        delegate = original.getKlass().getDeclaredConstructor().newInstance();
    }

    static void install(DefaultComponent target, ComponentInstance contributor) {
        // The platform descriptor declares Class<JsonESDocumentWriter> even for subclasses.
        ElasticSearchDocWriterDescriptor descriptor = new ElasticSearchDocWriterDescriptor() {
            @Override
            @SuppressWarnings("unchecked")
            public Class<JsonESDocumentWriter> getKlass() {
                return (Class<JsonESDocumentWriter>) (Class<?>) SearchWriter.class;
            }
        };
        target.registerContribution(descriptor, AssetSearchContribution.POINT, contributor);
    }

    @Override
    public void writeESDocument(JsonGenerator output, DocumentModel document, String[] schemas,
            Map<String, String> contextParameters) throws IOException {
        delegate.writeESDocument(AssetSearchJson.filter(output), document, schemas, contextParameters);
    }
}
