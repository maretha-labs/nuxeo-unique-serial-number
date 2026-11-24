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

import jakarta.transaction.Synchronization;

import org.nuxeo.runtime.transaction.TransactionHelper;

/** Binds a cleanup action to the current Nuxeo transaction on either outcome. */
final class TransactionCompletion {

    private TransactionCompletion() {
    }

    static void after(Runnable cleanup) {
        TransactionHelper.registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                cleanup.run();
            }
        });
    }
}
