package io.maretha.asset.usn.search;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.StringWriter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.search.SearchIndexDescriptor;
import org.nuxeo.ecm.core.search.index.DefaultIndexingJsonWriter;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.impl.ComponentManagerImpl;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import com.fasterxml.jackson.core.JsonGenerator;

@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.asset.usn.core")
@Deploy("org.nuxeo.ecm.core.search:OSGI-INF/search-service.xml")
@Deploy("org.nuxeo.ecm.core.search:OSGI-INF/repository-search-client-factory-service.xml")
@Deploy("org.nuxeo.ecm.core.search:OSGI-INF/repository-search-client-factory-contrib.xml")
@Deploy("io.maretha.asset.usn.tests:OSGI-INF/usn-test-search-index.xml")
public class TestAssetSearchWriter extends SearchWriterFixture {
    @Test
    public void stockAndCustomWritersKeepTheirConfiguredClientsAndFields() throws Exception {
        var registry = ((ComponentManagerImpl) Framework.getRuntime().getComponentManager()).getDescriptors();
        DocumentModel asset = numberedAsset();
        for (String name : new String[]{"stock-fixture", "custom-fixture"}) {
            SearchIndexDescriptor descriptor = registry.getDescriptor(AssetSearchContribution.TARGET,
                    AssetSearchContribution.POINT, name);
            assertEquals("repository", descriptor.getClient());
            assertEquals("test", descriptor.getRepositoryName());
            var out = new StringWriter();
            try (var generator = JSON.createGenerator(out)) {
                descriptor.newWriterInstance().writeDocument(generator, asset);
            }
            var data = JSON.readTree(out.toString());
            assertDocument(data);
            assertEquals(name.equals("custom-fixture"), data.has("custom:label"));
        }
    }

    public static class CustomWriter extends DefaultIndexingJsonWriter {
        @Override
        protected void writeSchemas(JsonGenerator json, DocumentModel doc) throws IOException {
            super.writeSchemas(json, doc);
            json.writeStringField("custom:label", "example");
        }
    }
}
