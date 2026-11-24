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
package io.maretha.asset.usn.operation;

import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_LONG;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_STRING;
import static io.maretha.asset.usn.AssetUsnConstants.SCHEMA_USN;
import static io.maretha.asset.usn.service.UsnAllocationException.SC_BAD_REQUEST;
import static io.maretha.asset.usn.service.UsnAllocationException.SC_CONFLICT;

import static org.nuxeo.ecm.core.api.security.SecurityConstants.WRITE_PROPERTIES;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.runtime.api.Framework;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.maretha.asset.usn.service.UsnAllocationException;
import io.maretha.asset.usn.service.UsnReservation;
import io.maretha.asset.usn.service.UsnService;

/**
 * Automation operation for clients that reserve a USN before saving an Asset.
 * <p>
 * Endpoint: {@code POST /nuxeo/api/v1/automation/Asset.ReserveUsn}
 * <br>
 * Parameters: {@code usnDate} (required, {@code YYYY-MM-DD}, any past or future date), {@code docId} (optional Nuxeo
 * UUID {@code ecm:id} when reserving on Edit for an existing Asset that has no USN yet).
 */
@Operation(id = ReserveUsnOperation.ID, category = Constants.CAT_DOCUMENT, label = "Reserve Asset USN",
        description = "Reserves a unique serial number (USN) for the requested date and authenticated caller.")
public class ReserveUsnOperation {

    public static final String ID = "Asset.ReserveUsn";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Context
    protected CoreSession session;

    @Param(name = "usnDate", required = true,
            description = "USN date in YYYY-MM-DD format (any past or future date with a 4-digit year).")
    protected String usnDate;

    @Param(name = "docId", required = false,
            description = "Optional Nuxeo document UUID (ecm:id) when adding a USN to an existing Asset on Edit.")
    protected String docId;

    @OperationMethod
    public Blob run() throws IOException {
        UsnService usnService = Framework.getService(UsnService.class);
        LocalDate parsedDate = usnService.parseUsnDate(usnDate);
        NuxeoPrincipal principal = session.getPrincipal();
        if (principal == null || principal.isAnonymous()) {
            throw new UsnAllocationException("Sign in to reserve a USN", 403);
        }
        String username = principal.getName();
        String normalizedDocId = StringUtils.trimToNull(docId);

        if (normalizedDocId != null) {
            DocumentModel existing = session.getDocument(new IdRef(normalizedDocId));
            if (!session.hasPermission(existing.getRef(), WRITE_PROPERTIES)) {
                throw new UsnAllocationException("WriteProperties is required to reserve a USN for this Asset", 403);
            }
            if (existing.isVersion() || existing.isProxy()) {
                throw new UsnAllocationException("Reserve an edit USN on the live document", SC_CONFLICT);
            }
            if (!existing.hasSchema(SCHEMA_USN)) {
                throw new UsnAllocationException("Document " + normalizedDocId + " does not have the usn schema",
                        SC_BAD_REQUEST);
            }
            if (existing.getPropertyValue(PROP_USN_STRING) != null
                    || existing.getPropertyValue(PROP_USN_LONG) != null) {
                throw new UsnAllocationException(
                        "Document " + normalizedDocId + " already has a USN and cannot be assigned another",
                        SC_CONFLICT);
            }
        }

        UsnReservation reservation = usnService.reserveUsn(parsedDate, username, normalizedDocId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usn_string", reservation.getUsnString());
        body.put("usn_long", reservation.getUsnLong());
        body.put("usn_date", reservation.getUsnDate());
        return Blobs.createJSONBlob(MAPPER.writeValueAsString(body));
    }
}
