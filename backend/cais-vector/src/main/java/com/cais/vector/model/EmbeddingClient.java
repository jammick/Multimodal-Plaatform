package com.cais.vector.model;

import java.util.List;

public interface EmbeddingClient {
    List<Float> embed(String text, String domain);
    default List<Float> embed(String text, String domain, String expectedModel) {
        return embed(text, domain);
    }
    boolean enabled();
}
