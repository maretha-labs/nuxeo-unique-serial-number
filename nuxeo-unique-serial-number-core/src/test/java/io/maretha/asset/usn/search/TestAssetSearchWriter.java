package io.maretha.asset.usn.search;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.elasticsearch.ElasticSearchComponent;
import org.nuxeo.elasticsearch.io.JsonESDocumentWriter;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.Assert.*;

@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.asset.usn.core")
@Deploy("org.nuxeo.elasticsearch.core:OSGI-INF/elasticsearch-service.xml")
@Deploy("org.nuxeo.elasticsearch.core:OSGI-INF/elasticsearch-service-contrib.xml")
@WithFrameworkProperty(name = "elasticsearch.enabled", value = "false")
public class TestAssetSearchWriter extends SearchWriterFixture {
    private JsonNode serialize() throws Exception {
        var target = (ElasticSearchComponent) Framework.getRuntime().getComponent(AssetSearchContribution.TARGET);
        // Elasticsearch can be disabled; exercise the writer installed in its actual extension slot.
        var slot = ElasticSearchComponent.class.getDeclaredField("jsonESDocumentWriter");
        slot.setAccessible(true);
        var writer = (JsonESDocumentWriter) slot.get(target);
        assertEquals(SearchWriter.class, writer.getClass());
        var out = new StringWriter();
        try (var generator = JSON.createGenerator(out)) {
            writer.writeESDocument(generator, numberedAsset(), null, null);
        }
        return JSON.readTree(out.toString());
    }

    @Test
    public void stockWriter() throws Exception {
        assertDocument(serialize());
    }

    @Test
    @Deploy("io.maretha.asset.usn.tests:OSGI-INF/usn-test-es-writer.xml")
    public void customWriter() throws Exception {
        JsonNode data = serialize();
        assertDocument(data);
        assertEquals("example", data.get("custom:label").asText());
    }

    public static class CustomWriter extends JsonESDocumentWriter {
        @Override
        protected void writeContextParameters(JsonGenerator json, DocumentModel document, Map<String, String> params)
                throws IOException {
            super.writeContextParameters(json, document, params);
            json.writeStringField("custom:label", "example");
        }
    }
}
