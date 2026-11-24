package io.maretha.asset.usn.search;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.filter.FilteringGeneratorDelegate;
import com.fasterxml.jackson.core.filter.TokenFilter;

/** Filters only the top-level schema properties emitted to the search client. */
final class AssetSearchJson {
    private static final TokenFilter PROPERTIES = new TokenFilter() {
        @Override
        public TokenFilter includeProperty(String field) {
            return field.startsWith("usn:") || field.startsWith("assetmedia:") ? null : INCLUDE_ALL;
        }
    };

    private AssetSearchJson() {
    }

    static JsonGenerator filter(JsonGenerator target) {
        return new FilteringGeneratorDelegate(target, PROPERTIES, TokenFilter.Inclusion.INCLUDE_NON_NULL, true);
    }
}
