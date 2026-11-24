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

import java.util.Objects;

/**
 * Immutable representation of a reserved or allocated Unique Serial Number (USN).
 */
public final class UsnReservation {

    private final String usnString;

    private final long usnLong;

    private final String usnDate;

    public UsnReservation(String usnString, long usnLong, String usnDate) {
        this.usnString = Objects.requireNonNull(usnString, "usnString must not be null");
        this.usnLong = usnLong;
        this.usnDate = Objects.requireNonNull(usnDate, "usnDate must not be null");
    }

    public String getUsnString() {
        return usnString;
    }

    public long getUsnLong() {
        return usnLong;
    }

    public String getUsnDate() {
        return usnDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UsnReservation that)) {
            return false;
        }
        return usnLong == that.usnLong && usnString.equals(that.usnString) && usnDate.equals(that.usnDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(usnString, usnLong, usnDate);
    }

    @Override
    public String toString() {
        return "UsnReservation{usnString='" + usnString + "', usnLong=" + usnLong + ", usnDate='" + usnDate + "'}";
    }
}
