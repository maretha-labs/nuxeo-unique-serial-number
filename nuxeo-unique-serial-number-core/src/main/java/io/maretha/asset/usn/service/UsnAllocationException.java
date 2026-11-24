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

import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Exception raised when a USN cannot be reserved, validated, consumed, or modified.
 */
public class UsnAllocationException extends NuxeoException {

    private static final long serialVersionUID = 1L;

    public static final int SC_BAD_REQUEST = 400;

    public static final int SC_CONFLICT = 409;

    public static final int SC_UNPROCESSABLE_ENTITY = 422;

    public UsnAllocationException(String message, int statusCode) {
        super(message, statusCode);
    }

    public UsnAllocationException(String message, Throwable cause, int statusCode) {
        super(message, cause, statusCode);
    }
}
