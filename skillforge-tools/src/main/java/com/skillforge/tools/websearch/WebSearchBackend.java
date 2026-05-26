package com.skillforge.tools.websearch;

public interface WebSearchBackend {
    SearchResult search(String query, SearchOptions options) throws WebSearchException;

    default SearchResult search(String query, int maxResults) throws WebSearchException {
        return search(query, SearchOptions.withMaxResults(maxResults));
    }

    WebSearchBackendName name();

    boolean isConfigured();
}
