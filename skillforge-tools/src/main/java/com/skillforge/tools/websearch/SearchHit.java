package com.skillforge.tools.websearch;

public record SearchHit(String title, String url, String snippet, int rank,
                        String publishedDate, Double score) {
    public SearchHit(String title, String url, String snippet, int rank) {
        this(title, url, snippet, rank, null, null);
    }
}
