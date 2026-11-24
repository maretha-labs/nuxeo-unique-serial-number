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

import static io.maretha.asset.usn.AssetUsnConstants.CONF_EDIT_LOCK_TTL_SECONDS;
import static io.maretha.asset.usn.AssetUsnConstants.CONF_KV_STORE_NAME;
import static io.maretha.asset.usn.AssetUsnConstants.CONF_MAX_SUFFIX;
import static io.maretha.asset.usn.AssetUsnConstants.CONF_MIN_SUFFIX;
import static io.maretha.asset.usn.AssetUsnConstants.CONF_RESERVATION_TTL_SECONDS;
import static io.maretha.asset.usn.AssetUsnConstants.DEFAULT_EDIT_LOCK_TTL_SECONDS;
import static io.maretha.asset.usn.AssetUsnConstants.DEFAULT_KV_STORE_NAME;
import static io.maretha.asset.usn.AssetUsnConstants.DEFAULT_MAX_SUFFIX;
import static io.maretha.asset.usn.AssetUsnConstants.DEFAULT_MIN_SUFFIX;
import static io.maretha.asset.usn.AssetUsnConstants.DEFAULT_RESERVATION_TTL_SECONDS;
import static io.maretha.asset.usn.AssetUsnConstants.KV_LOCK_PREFIX;
import static io.maretha.asset.usn.AssetUsnConstants.KV_RES_PREFIX;
import static io.maretha.asset.usn.AssetUsnConstants.KV_SEQ_PREFIX;
import static io.maretha.asset.usn.AssetUsnConstants.MAX_USN_YEAR;
import static io.maretha.asset.usn.AssetUsnConstants.MIN_USN_YEAR;
import static io.maretha.asset.usn.service.UsnAllocationException.SC_BAD_REQUEST;
import static io.maretha.asset.usn.service.UsnAllocationException.SC_CONFLICT;
import static io.maretha.asset.usn.service.UsnAllocationException.SC_UNPROCESSABLE_ENTITY;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.kv.MemKeyValueStore;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Issues matching string/long USNs using one atomic Nuxeo key-value increment per allocation. */
public class UsnServiceImpl extends DefaultComponent implements UsnService {

    private static final Logger log = LogManager.getLogger(UsnServiceImpl.class);

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private record Allocator(KeyValueStore store, long minimum, long maximum) {
    }

    private volatile Allocator allocator;

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        allocator = null;
        super.stop(context);
    }

    @Override
    public UsnReservation reserveUsn(LocalDate usnDate, String username, String docId) {
        validateUsnDate(usnDate);
        if (StringUtils.isBlank(username)) {
            throw new UsnAllocationException("Authenticated username is required to reserve a USN", SC_BAD_REQUEST);
        }

        long ttl = getReservationTtlSeconds();
        UsnReservation allocated = allocateNextForDate(usnDate);
        String resKey = KV_RES_PREFIX + allocated.getUsnString();
        String payload = buildReservationPayload(username, allocated.getUsnDate(), StringUtils.trimToNull(docId), ttl);

        KeyValueStore kv = getAllocator().store();
        boolean stored = kv.compareAndSet(resKey, null, payload, ttl);
        if (!stored) {
            throw new UsnAllocationException("USN reservation collision detected for " + allocated.getUsnString(),
                    SC_CONFLICT);
        }

        log.debug("Reserved USN {} for date={} user={} docId={}", allocated.getUsnString(), allocated.getUsnDate(),
                username, docId);
        return allocated;
    }

    @Override
    public UsnReservation allocateDirect(LocalDate usnDate) {
        validateUsnDate(usnDate);
        return allocateNextForDate(usnDate);
    }

    @Override
    public void consumeReservation(String usnString, Long usnLong, LocalDate usnDate, String username,
            String expectedDocId) {
        validateFormatAndBounds(usnString, usnLong, usnDate);
        if (StringUtils.isBlank(username)) {
            throw new UsnAllocationException("Authenticated username is required to consume a USN reservation",
                    SC_BAD_REQUEST);
        }

        KeyValueStore kv = getAllocator().store();
        String resKey = KV_RES_PREFIX + usnString;
        String existingJson = kv.getString(resKey);
        if (existingJson == null) {
            throw new UsnAllocationException(
                    "USN reservation not found, expired, or already consumed: " + usnString, SC_CONFLICT);
        }

        Map<String, String> reservation = parseReservationPayload(existingJson, usnString);
        String expiration = reservation.get("expiresAt");
        if (expiration == null) {
            throw new UsnAllocationException("Reserve a new USN: the reservation has no expiration", SC_CONFLICT);
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(expiration);
        } catch (DateTimeParseException e) {
            throw new UsnAllocationException("Invalid USN reservation expiration", e, SC_CONFLICT);
        }
        String resUser = reservation.get("user");
        String resDate = reservation.get("usnDate");
        String resEcmId = reservation.get("ecmId");
        if (!Objects.equals(resEcmId == null ? "create" : "document", reservation.get("scope"))) {
            throw new UsnAllocationException("Invalid scope on USN reservation", SC_CONFLICT);
        }

        if (!Objects.equals(resUser, username)) {
            throw new UsnAllocationException(
                    "USN " + usnString + " is reserved by a different user and cannot be claimed by " + username,
                    SC_CONFLICT);
        }
        if (!Objects.equals(resDate, usnDate.toString())) {
            throw new UsnAllocationException(
                    "USN " + usnString + " was reserved for date " + resDate + " but submitted with " + usnDate,
                    SC_UNPROCESSABLE_ENTITY);
        }
        String normalizedExpectedDocId = StringUtils.trimToNull(expectedDocId);
        if (!Objects.equals(normalizedExpectedDocId, resEcmId)) {
            throw new UsnAllocationException("USN reservation does not match the target document", SC_CONFLICT);
        }

        // TTL cleanup can be asynchronous. Check the application deadline at the point of consumption.
        if (!kv.compareAndSet(resKey, existingJson, null, 0L) || !Instant.now().isBefore(expiresAt)) {
            throw new UsnAllocationException("USN reservation expired or was already consumed: " + usnString,
                    SC_CONFLICT);
        }
        log.debug("Consumed USN reservation {} for user={} docId={}", usnString, username, normalizedExpectedDocId);
    }

    @Override
    public boolean acquireEditLock(String docId, String usnString) {
        if (StringUtils.isBlank(docId) || StringUtils.isBlank(usnString)) {
            throw new UsnAllocationException("Document UUID and a lock owner token are required", SC_BAD_REQUEST);
        }
        int remaining = TransactionHelper.getTransactionTimeToLive();
        if (remaining < 0 && TransactionHelper.isTransactionActive()) {
            throw unavailable("USN edit lock requires a known transaction deadline");
        }
        long ttl = Math.max(getEditLockTtlSeconds(), (long) remaining + 30);
        return getAllocator().store().compareAndSet(KV_LOCK_PREFIX + docId, null, usnString, ttl);
    }

    @Override
    public void releaseEditLock(String docId, String usnString) {
        if (StringUtils.isNotBlank(docId) && StringUtils.isNotBlank(usnString)) {
            getAllocator().store().compareAndSet(KV_LOCK_PREFIX + docId, usnString, null, 0L);
        }
    }

    @Override
    public void validateFormatAndBounds(String usnString, Long usnLong, LocalDate usnDate) {
        validateUsnDate(usnDate);
        if (StringUtils.isBlank(usnString) || usnLong == null) {
            throw new UsnAllocationException("Both usn:usn_string and usn:usn_long are required",
                    SC_UNPROCESSABLE_ENTITY);
        }
        String trimmed = usnString.trim();
        if (!trimmed.equals(usnString) || !isAllAsciiDigits(trimmed)) {
            throw new UsnAllocationException("USN must contain digits only with no separators: " + usnString,
                    SC_UNPROCESSABLE_ENTITY);
        }

        Allocator settings = getAllocator();
        if (trimmed.length() != 14) {
            throw new UsnAllocationException("USN must have a YYYYMMDD date and a six-digit suffix",
                    SC_UNPROCESSABLE_ENTITY);
        }

        String expectedDatePrefix = usnDate.format(YYYYMMDD);
        if (!trimmed.startsWith(expectedDatePrefix)) {
            throw new UsnAllocationException(
                    "USN " + trimmed + " date prefix does not match usn:usn_date (" + usnDate + ")",
                    SC_UNPROCESSABLE_ENTITY);
        }

        long parsedLong;
        long suffix;
        try {
            parsedLong = Long.parseLong(trimmed);
            suffix = Long.parseLong(trimmed.substring(8));
        } catch (NumberFormatException e) {
            throw new UsnAllocationException("Invalid numeric USN: " + trimmed, e, SC_UNPROCESSABLE_ENTITY);
        }

        if (parsedLong != usnLong.longValue()) {
            throw new UsnAllocationException(
                    "usn:usn_string (" + trimmed + ") and usn:usn_long (" + usnLong + ") must match",
                    SC_UNPROCESSABLE_ENTITY);
        }
        if (suffix < settings.minimum() || suffix > settings.maximum()) {
            throw new UsnAllocationException(
                    "USN suffix " + suffix + " is outside allowed range [" + settings.minimum() + ", "
                            + settings.maximum() + "]",
                    SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public LocalDate parseUsnDate(String rawDate) {
        if (StringUtils.isBlank(rawDate)) {
            throw new UsnAllocationException("usnDate is required", SC_BAD_REQUEST);
        }
        String trimmed = rawDate.trim();
        try {
            LocalDate date;
            if (trimmed.length() == 10) {
                date = LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
            } else if (trimmed.length() == 8 && isAllAsciiDigits(trimmed)) {
                date = LocalDate.parse(trimmed, YYYYMMDD);
            } else {
                date = Instant.parse(trimmed).atZone(ZoneOffset.UTC).toLocalDate();
            }
            validateUsnDate(date);
            return date;
        } catch (DateTimeParseException e) {
            throw new UsnAllocationException("Invalid usnDate format (expected YYYY-MM-DD): " + rawDate, e,
                    SC_BAD_REQUEST);
        }
    }

    @Override
    public LocalDate toUtcLocalDate(Calendar calendar) {
        if (calendar == null) {
            return null;
        }
        return calendar.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    @Override
    public Calendar toUtcNoonCalendar(LocalDate date) {
        validateUsnDate(date);
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC));
        cal.setTimeInMillis(date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
        return cal;
    }

    protected UsnReservation allocateNextForDate(LocalDate usnDate) {
        Allocator settings = getAllocator();
        long capacity = settings.maximum() - settings.minimum() + 1;
        String day = usnDate.format(YYYYMMDD);
        long ordinal = settings.store().addAndGet(KV_SEQ_PREFIX + day, 1);
        if (ordinal < 1 || ordinal > capacity) {
            throw new UsnAllocationException(
                    "USN capacity exhausted for date " + usnDate + " (max " + capacity + " per date)", SC_CONFLICT);
        }
        String text = day + (settings.minimum() + ordinal - 1);
        return new UsnReservation(text, Long.parseLong(text), usnDate.toString());
    }

    private Allocator getAllocator() {
        Allocator current = allocator;
        return current == null ? initializeAllocator() : current;
    }

    private synchronized Allocator initializeAllocator() {
        if (allocator == null) {
            long min = getMinSuffix();
            long max = getMaxSuffix();
            if (min < 100000 || max > 999999 || min > max) {
                throw unavailable("USN bounds must form an ascending six-digit suffix range");
            }
            // Earlier installations with a generation setting defaulted to usnStore.
            // Preserve the same high-water counters even when no store name was explicitly set.
            String defaultName = StringUtils.isBlank(Framework.getProperty("maretha.asset.usn.generation"))
                    ? DEFAULT_KV_STORE_NAME : "usnStore";
            String name = StringUtils.defaultIfBlank(Framework.getProperty(CONF_KV_STORE_NAME), defaultName).trim();
            KeyValueStore store = Framework.getService(KeyValueService.class).getKeyValueStore(name);
            if (store == null || (store instanceof MemKeyValueStore && !Framework.isTestModeSet())) {
                throw unavailable("USN issuance requires a durable, shared Nuxeo key-value store");
            }
            allocator = new Allocator(store, min, max);
        }
        return allocator;
    }

    private static UsnAllocationException unavailable(String message) {
        return new UsnAllocationException(message, 503);
    }

    protected void validateUsnDate(LocalDate usnDate) {
        if (usnDate == null) {
            throw new UsnAllocationException("usn:usn_date is required when assigning a USN", SC_UNPROCESSABLE_ENTITY);
        }
        int year = usnDate.getYear();
        if (year < MIN_USN_YEAR || year > MAX_USN_YEAR) {
            throw new UsnAllocationException(
                    "usn:usn_date year must be a 4-digit year between " + MIN_USN_YEAR + " and " + MAX_USN_YEAR
                            + " (got " + year + ")",
                    SC_UNPROCESSABLE_ENTITY);
        }
    }

    protected String buildReservationPayload(String username, String usnDateIso, String ecmId, long ttl) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("scope", ecmId == null ? "create" : "document");
        payload.put("user", username);
        payload.put("usnDate", usnDateIso);
        payload.put("expiresAt", Instant.now().plusSeconds(ttl).toString());
        if (ecmId != null) {
            payload.put("ecmId", ecmId);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (IOException e) {
            throw new UsnAllocationException("Failed to serialize USN reservation payload", e, 500);
        }
    }

    protected Map<String, String> parseReservationPayload(String json, String usnString) {
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            throw new UsnAllocationException("Corrupted USN reservation entry for " + usnString, e, 500);
        }
    }

    protected long getMinSuffix() {
        return getLongProperty(CONF_MIN_SUFFIX, DEFAULT_MIN_SUFFIX);
    }

    protected long getMaxSuffix() {
        return getLongProperty(CONF_MAX_SUFFIX, DEFAULT_MAX_SUFFIX);
    }

    protected long getReservationTtlSeconds() {
        return ttlSeconds(CONF_RESERVATION_TTL_SECONDS, DEFAULT_RESERVATION_TTL_SECONDS);
    }

    protected long getEditLockTtlSeconds() {
        return ttlSeconds(CONF_EDIT_LOCK_TTL_SECONDS, DEFAULT_EDIT_LOCK_TTL_SECONDS);
    }

    private long ttlSeconds(String key, long defaultValue) {
        long seconds = getLongProperty(key, defaultValue);
        if (seconds > Integer.MAX_VALUE) {
            throw unavailable("USN TTL exceeds supported seconds: " + key);
        }
        return seconds;
    }

    protected long getLongProperty(String key, long defaultValue) {
        String raw = Framework.getProperty(key);
        if (StringUtils.isBlank(raw)) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw unavailable("USN configuration must be positive: " + key);
            }
            return value;
        } catch (NumberFormatException e) {
            throw unavailable("Invalid numeric USN configuration: " + key);
        }
    }

    protected static boolean isAllAsciiDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
