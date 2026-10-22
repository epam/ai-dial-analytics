package com.epam.aidial.analytics.sync.inline;

/** The APIs whose bodies carry inline files at fixed JSON locations (see {@link InlineFileLocations}). */
public enum Api {

    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES,
    EMBEDDINGS,
    MCP
}
