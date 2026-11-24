package io.maretha.asset.usn.search;

import static org.junit.Assert.*;

import java.util.Calendar;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

public abstract class SearchWriterFixture {
    protected static final ObjectMapper JSON = new ObjectMapper();
    @Inject protected CoreSession session;

    protected DocumentModel numberedAsset() {
        DocumentModel asset = session.createDocumentModel("/", "numbered", "Asset");
        asset.setPropertyValue("dc:title", "Catalogue title");
        asset.setPropertyValue("usn:usn_date", Calendar.getInstance());
        asset.setPropertyValue("usn:generate_usn", true);
        asset = session.createDocument(asset);
        assertNotNull(asset.getPropertyValue("usn:usn_long"));
        assertEquals(asset.getPropertyValue("usn:usn_long").toString(), asset.getPropertyValue("usn:usn_string"));
        return asset;
    }

    protected void assertDocument(JsonNode data) {
        assertEquals("Catalogue title", data.get("dc:title").asText());
        assertEquals("Asset", data.get("ecm:primaryType").asText());
        data.fieldNames().forEachRemaining(field ->
                assertFalse(field, field.startsWith("usn:") || field.startsWith("assetmedia:")));
    }
}
