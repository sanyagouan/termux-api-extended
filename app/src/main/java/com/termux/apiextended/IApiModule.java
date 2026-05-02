package com.termux.apiextended;

/**
 * Base interface for all API modules.
 * Each API module processes a command and writes JSON output.
 */
public interface IApiModule {
    /**
     * Execute the given command.
     *
     * @param context  Android context
     * @param method   Sub-method (e.g., "scan", "connect")
     * @param params   JSON parameters as raw string
     * @return JSON string response
     */
    String execute(android.content.Context context, String method, String params);
}
