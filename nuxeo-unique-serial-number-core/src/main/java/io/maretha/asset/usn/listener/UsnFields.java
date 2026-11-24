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
package io.maretha.asset.usn.listener;

import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_DATE;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_LONG;
import static io.maretha.asset.usn.AssetUsnConstants.PROP_USN_STRING;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Objects;

import org.nuxeo.ecm.core.api.DocumentModel;
import io.maretha.asset.usn.service.UsnAllocationException;

/** Snapshot of the stored representations, including numbered and unnumbered historical documents. */
record UsnFields(String text, Long number, Calendar date) implements Serializable {

    static final UsnFields EMPTY = new UsnFields(null, null, null);

    static UsnFields read(DocumentModel doc) {
        Calendar date = (Calendar) doc.getPropertyValue(PROP_USN_DATE);
        return new UsnFields((String) doc.getPropertyValue(PROP_USN_STRING), (Long) doc.getPropertyValue(PROP_USN_LONG),
                date == null ? null : (Calendar) date.clone());
    }

    boolean numbered() {
        return text != null || number != null;
    }

    UsnFields completed() {
        if (!numbered()) {
            throw new UsnAllocationException("A stored USN representation is required", 422);
        }
        String value = text == null ? number.toString() : text;
        try {
            return new UsnFields(value, number == null ? Long.valueOf(value) : number, date);
        } catch (NumberFormatException e) {
            throw new UsnAllocationException("USN must contain canonical decimal digits", e, 422);
        }
    }

    boolean matches(UsnFields other) {
        return Objects.equals(text, other.text) && Objects.equals(number, other.number)
                && Objects.equals(date == null ? null : date.toInstant(), other.date == null ? null : other.date.toInstant());
    }

    void write(DocumentModel doc) {
        doc.setPropertyValue(PROP_USN_STRING, text);
        doc.setPropertyValue(PROP_USN_LONG, number);
        doc.setPropertyValue(PROP_USN_DATE, date);
    }
}
