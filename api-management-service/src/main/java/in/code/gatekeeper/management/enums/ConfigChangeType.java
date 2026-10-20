package in.code.gatekeeper.management.enums;

// What bumped an organization's configuration version. Status changes are the platform admin's and never rolled
// back, so they take no place in the rollback history.
public enum ConfigChangeType {
    ORGANIZATION_CREATED(true),
    ROUTE_CREATED(true),
    ROUTE_UPDATED(true),
    ROUTE_DEACTIVATED(true),
    ROUTE_ACTIVATED(true),
    TOKEN_SETTINGS_SAVED(true),
    ROLLED_BACK(true),
    ORGANIZATION_SUSPENDED(false),
    ORGANIZATION_ACTIVATED(false);

    private final boolean contentChange;

    ConfigChangeType(boolean contentChange) {
        this.contentChange = contentChange;
    }

    public boolean isContentChange() {
        return contentChange;
    }
}
