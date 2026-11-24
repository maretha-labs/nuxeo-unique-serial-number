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
package io.maretha.asset.usn.service;

import java.time.LocalDate;
import java.util.Calendar;

/**
 * Service managing Unique Serial Number (USN) reservation, direct allocation, validation, and atomic consumption
 * backed by Nuxeo's KeyValueService.
 */
public interface UsnService {

    /**
     * Atomically reserves a unique USN for the given USN Date (any valid past or future date with a 4-digit year).
     *
     * @param usnDate  the user-selected USN date
     * @param username the Nuxeo principal reserving the USN
     * @param docId    optional Nuxeo document UUID ({@code ecm:id}) when reserving from the Edit form of an existing
     *                 Asset without a USN; {@code null} when reserving on creation
     * @return the reserved USN values ({@code usn_string}, {@code usn_long}, {@code usn_date})
     */
    UsnReservation reserveUsn(LocalDate usnDate, String username, String docId);

    /**
     * Atomically allocates a unique USN directly without storing an intermediate reservation record. Used for
     * bulk-import / server-side auto-generation during {@code aboutToCreate}.
     *
     * @param usnDate the USN date
     * @return the allocated USN values
     */
    UsnReservation allocateDirect(LocalDate usnDate);

    /**
     * Validates a pre-reserved USN and atomically consumes (deletes) its ephemeral reservation record.
     *
     * @param usnString     the string representation of the USN
     * @param usnLong       the long representation of the USN
     * @param usnDate       the document's USN date
     * @param username      the Nuxeo principal committing the document
     * @param expectedDocId {@code null} during creation, or the document's Nuxeo UUID ({@code ecm:id}) during edit
     */
    void consumeReservation(String usnString, Long usnLong, LocalDate usnDate, String username, String expectedDocId);

    /** Validates a reservation explicitly bound to the stable UUID being imported. */
    default void consumeImportReservation(String usnString, Long usnLong, LocalDate usnDate, String username,
            String docId) {
        consumeImportReservation(usnString, usnLong, usnDate, username, docId, null);
    }

    /** Validates an import reservation in the target session. */
    default void consumeImportReservation(String usnString, Long usnLong, LocalDate usnDate, String username,
            String docId, String repository) {
        if (docId == null || docId.isBlank()) {
            throw new UsnAllocationException("Native import requires a stable document UUID",
                    UsnAllocationException.SC_BAD_REQUEST);
        }
        consumeReservation(usnString, usnLong, usnDate, username, docId);
    }

    /**
     * Acquires a transaction-duration mutex keyed by Nuxeo UUID ({@code ecm:id}) to prevent concurrent edits from simultaneously
     * assigning a USN to the same Asset.
     *
     * @param docId     the Nuxeo UUID ({@code ecm:id})
     * @param usnString an opaque lock ownership token
     * @return {@code true} if the lock was acquired
     */
    boolean acquireEditLock(String docId, String usnString);

    /**
     * Releases a previously acquired per-UUID edit lock if it is still held for the given USN.
     *
     * @param docId     the Nuxeo UUID ({@code ecm:id})
     * @param usnString the same ownership token used to acquire the lock
     */
    void releaseEditLock(String docId, String usnString);

    /**
     * Validates that {@code usnString}, {@code usnLong}, and {@code usnDate} are mutually consistent and within the
     * configured per-date suffix bounds.
     */
    void validateFormatAndBounds(String usnString, Long usnLong, LocalDate usnDate);

    /**
     * Parses an ISO-8601 date-only string ({@code YYYY-MM-DD}) or ISO timestamp into a {@link LocalDate}.
     */
    LocalDate parseUsnDate(String rawDate);

    /**
     * Converts a Nuxeo {@link Calendar} property value to its UTC {@link LocalDate}.
     */
    LocalDate toUtcLocalDate(Calendar calendar);

    /**
     * Normalizes a {@link LocalDate} to UTC noon ({@code YYYY-MM-DDT12:00:00Z}) for timezone-safe persistence in
     * {@code usn:usn_date}.
     */
    Calendar toUtcNoonCalendar(LocalDate date);
}
