package io.maretha.asset.usn;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.restapi.test.RestServerFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

@RunWith(FeaturesRunner.class)
@Features(RestServerFeature.class)
@Deploy("io.maretha.asset.usn.core")
public class TestAssetRestCreate {
    @Inject protected RestServerFeature server;
    @Inject protected CoreSession session;

    @Test
    public void firstResponseContainsBothRepresentations() throws Exception {
        var payload = """
                {"entity-type":"document","name":"numbered","type":"Asset","properties":{
                "dc:title":"Numbered","usn:usn_date":"2026-11-24T12:00:00Z","usn:generate_usn":true}}
                """;
        var response = create(payload);
        assertEquals(response.body(), 201, response.statusCode());
        var props = new ObjectMapper().readTree(response.body()).required("properties");
        String value = props.required("usn:usn_string").textValue();
        assertTrue(value, value.matches("20261124[0-9]{6}"));
        assertTrue(props.required("usn:usn_long").isIntegralNumber());
        assertEquals(Long.parseLong(value), props.get("usn:usn_long").longValue());
        assertFalse(props.path("usn:generate_usn").asBoolean());
    }
    @Test
    public void aClientCannotSupplyAnUnreservedUsn() throws Exception {
        String payload = """
                {"entity-type":"document","name":"unreserved","type":"Asset","properties":{
                "dc:title":"Unreserved","usn:usn_date":"2035-12-12T12:00:00Z",
                "usn:usn_string":"20351212100000","usn:usn_long":20351212100000}}
                """;
        assertEquals(409, create(payload).statusCode());
        assertFalse(session.exists(new org.nuxeo.ecm.core.api.PathRef("/unreserved")));
    }

    private HttpResponse<String> create(String payload) throws Exception {
        // Standard account supplied by the isolated Nuxeo REST test fixture.
        String auth = Base64.getEncoder().encodeToString("Administrator:Administrator".getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(server.getRestApiUrl() + "/id/" + session.getRootDocument().getId()))
                .header("Authorization", "Basic " + auth).header("Content-Type", "application/json")
                .header("X-NXDocumentProperties", "usn").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
