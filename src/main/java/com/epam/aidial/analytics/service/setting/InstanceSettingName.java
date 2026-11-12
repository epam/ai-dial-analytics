package com.epam.aidial.analytics.service.setting;

/**
 * The closed set of instance settings. The database {@code CHECK} constraint on {@code instance_setting.name}
 * lists the same names, so a row cannot exist outside this set.
 */
public enum InstanceSettingName {

    /** Which catalog entity holds session rows, and which of its fields play the session roles. */
    SESSIONS_SOURCE("sessions-source"),

    /** The DIAL application routes an enrichment pipeline may call. */
    APP_ROUTES("app-routes");

    private final String wireName;

    InstanceSettingName(String wireName) {
        this.wireName = wireName;
    }

    /** The name as stored, as it appears in the URL and as the audit resource id. */
    public String wireName() {
        return wireName;
    }
}
