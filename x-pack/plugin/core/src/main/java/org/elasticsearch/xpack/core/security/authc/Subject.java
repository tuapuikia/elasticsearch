/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.security.authc;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.core.security.authc.service.ServiceAccountSettings;
import org.elasticsearch.xpack.core.security.authz.store.RoleReference;
import org.elasticsearch.xpack.core.security.user.AnonymousUser;
import org.elasticsearch.xpack.core.security.user.User;

import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.core.security.authc.Authentication.VERSION_API_KEY_ROLES_AS_BYTES;
import static org.elasticsearch.xpack.core.security.authc.AuthenticationField.API_KEY_LIMITED_ROLE_DESCRIPTORS_KEY;
import static org.elasticsearch.xpack.core.security.authc.AuthenticationField.API_KEY_ROLE_DESCRIPTORS_KEY;

/**
 * A subject is a more generic concept similar to user and associated to the current authentication.
 * It is more generic than user because it can also represent API keys and service accounts.
 * It also contains authentication level information, e.g. realm and metadata so that it can answer
 * queries in a better encapsulated way.
 */
public class Subject {

    public enum Type {
        USER,
        API_KEY,
        SERVICE_ACCOUNT,
    }

    private final Version version;
    private final User user;
    private final Authentication.RealmRef realm;
    private final Type type;
    private final Map<String, Object> metadata;

    public Subject(User user, Authentication.RealmRef realm) {
        this(user, realm, Version.CURRENT, Map.of());
    }

    public Subject(User user, Authentication.RealmRef realm, Version version, Map<String, Object> metadata) {
        this.version = version;
        this.user = user;
        this.realm = realm;
        // Realm can be null for run-as user if it does not exist. Pretend it is a user and it will be rejected later in authorization
        // This is to be consistent with existing behaviour.
        if (realm == null) {
            this.type = Type.USER;
        } else if (AuthenticationField.API_KEY_REALM_TYPE.equals(realm.getType())) {
            assert AuthenticationField.API_KEY_REALM_NAME.equals(realm.getName()) : "api key realm name mismatch";
            this.type = Type.API_KEY;
        } else if (ServiceAccountSettings.REALM_TYPE.equals(realm.getType())) {
            assert ServiceAccountSettings.REALM_NAME.equals(realm.getName()) : "service account realm name mismatch";
            this.type = Type.SERVICE_ACCOUNT;
        } else {
            this.type = Type.USER;
        }
        this.metadata = metadata;
    }

    public User getUser() {
        return user;
    }

    public Authentication.RealmRef getRealm() {
        return realm;
    }

    public Type getType() {
        return type;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Return a List of RoleReferences that represents role definitions associated to the subject.
     * The final role of this subject should be the intersection of all role references in the list.
     */
    public List<RoleReference> getRoleReferences(@Nullable AnonymousUser anonymousUser) {
        switch (type) {
            case USER:
                return buildRoleReferencesForUser(anonymousUser);
            case API_KEY:
                return buildRoleReferencesForApiKey();
            case SERVICE_ACCOUNT:
                return List.of(new RoleReference.ServiceAccountRoleReference(user.principal()));
            default:
                assert false : "unknown subject type: [" + type + "]";
                throw new IllegalStateException("unknown subject type: [" + type + "]");
        }
    }

    private List<RoleReference> buildRoleReferencesForUser(AnonymousUser anonymousUser) {
        if (user.equals(anonymousUser)) {
            return List.of(new RoleReference.NamedRoleReference(user.roles()));
        }
        final String[] allRoleNames;
        if (anonymousUser == null || false == anonymousUser.enabled()) {
            allRoleNames = user.roles();
        } else {
            // TODO: should we validate enable status and length of role names on instantiation time of anonymousUser?
            if (anonymousUser.roles().length == 0) {
                throw new IllegalStateException("anonymous is only enabled when the anonymous user has roles");
            }
            allRoleNames = ArrayUtils.concat(user.roles(), anonymousUser.roles());
        }
        return List.of(new RoleReference.NamedRoleReference(allRoleNames));
    }

    private List<RoleReference> buildRoleReferencesForApiKey() {
        if (version.before(VERSION_API_KEY_ROLES_AS_BYTES)) {
            return buildRolesReferenceForApiKeyBwc();
        }
        final String apiKeyId = (String) metadata.get(AuthenticationField.API_KEY_ID_KEY);
        final BytesReference roleDescriptorsBytes = (BytesReference) metadata.get(API_KEY_ROLE_DESCRIPTORS_KEY);
        final BytesReference limitedByRoleDescriptorsBytes = getLimitedByRoleDescriptorsBytes();
        if (roleDescriptorsBytes == null && limitedByRoleDescriptorsBytes == null) {
            throw new ElasticsearchSecurityException("no role descriptors found for API key");
        }
        final RoleReference.ApiKeyRoleReference limitedByRoleReference = new RoleReference.ApiKeyRoleReference(
            apiKeyId,
            limitedByRoleDescriptorsBytes,
            RoleReference.ApiKeyRoleType.LIMITED_BY
        );
        if (isEmptyRoleDescriptorsBytes(roleDescriptorsBytes)) {
            return List.of(limitedByRoleReference);
        }
        return List.of(
            new RoleReference.ApiKeyRoleReference(apiKeyId, roleDescriptorsBytes, RoleReference.ApiKeyRoleType.ASSIGNED),
            limitedByRoleReference
        );
    }

    private boolean isEmptyRoleDescriptorsBytes(BytesReference roleDescriptorsBytes) {
        return roleDescriptorsBytes == null || (roleDescriptorsBytes.length() == 2 && "{}".equals(roleDescriptorsBytes.utf8ToString()));
    }

    private List<RoleReference> buildRolesReferenceForApiKeyBwc() {
        final String apiKeyId = (String) metadata.get(AuthenticationField.API_KEY_ID_KEY);
        final Map<String, Object> roleDescriptorsMap = getRoleDescriptorMap(API_KEY_ROLE_DESCRIPTORS_KEY);
        final Map<String, Object> limitedByRoleDescriptorsMap = getRoleDescriptorMap(API_KEY_LIMITED_ROLE_DESCRIPTORS_KEY);
        if (roleDescriptorsMap == null && limitedByRoleDescriptorsMap == null) {
            throw new ElasticsearchSecurityException("no role descriptors found for API key");
        } else {
            final RoleReference.BwcApiKeyRoleReference limitedByRoleReference = new RoleReference.BwcApiKeyRoleReference(
                apiKeyId,
                limitedByRoleDescriptorsMap,
                RoleReference.ApiKeyRoleType.LIMITED_BY
            );
            if (roleDescriptorsMap == null || roleDescriptorsMap.isEmpty()) {
                return List.of(limitedByRoleReference);
            } else {
                return List.of(
                    new RoleReference.BwcApiKeyRoleReference(apiKeyId, roleDescriptorsMap, RoleReference.ApiKeyRoleType.ASSIGNED),
                    limitedByRoleReference
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getRoleDescriptorMap(String key) {
        return (Map<String, Object>) metadata.get(key);
    }

    // This following fixed role descriptor is for fleet-server BWC on and before 7.14.
    // It is fixed and must NOT be updated when the fleet-server service account updates.
    // Package private for testing
    static final BytesArray FLEET_SERVER_ROLE_DESCRIPTOR_BYTES_V_7_14 = new BytesArray(
        "{\"elastic/fleet-server\":{\"cluster\":[\"monitor\",\"manage_own_api_key\"],"
            + "\"indices\":[{\"names\":[\"logs-*\",\"metrics-*\",\"traces-*\",\"synthetics-*\","
            + "\".logs-endpoint.diagnostic.collection-*\"],"
            + "\"privileges\":[\"write\",\"create_index\",\"auto_configure\"],\"allow_restricted_indices\":false},"
            + "{\"names\":[\".fleet-*\"],\"privileges\":[\"read\",\"write\",\"monitor\",\"create_index\",\"auto_configure\"],"
            + "\"allow_restricted_indices\":false}],\"applications\":[],\"run_as\":[],\"metadata\":{},"
            + "\"transient_metadata\":{\"enabled\":true}}}"
    );

    private BytesReference getLimitedByRoleDescriptorsBytes() {
        final BytesReference bytesReference = (BytesReference) metadata.get(API_KEY_LIMITED_ROLE_DESCRIPTORS_KEY);
        // Unfortunate BWC bug fix code
        if (bytesReference.length() == 2 && "{}".equals(bytesReference.utf8ToString())) {
            if (ServiceAccountSettings.REALM_NAME.equals(metadata.get(AuthenticationField.API_KEY_CREATOR_REALM_NAME))
                && "elastic/fleet-server".equals(user.principal())) {
                return FLEET_SERVER_ROLE_DESCRIPTOR_BYTES_V_7_14;
            }
        }
        return bytesReference;
    }
}
