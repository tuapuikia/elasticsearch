/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security.authz.store;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.util.automaton.Automaton;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ReleasableLock;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.AuthenticationContext;
import org.elasticsearch.xpack.core.security.authc.Subject;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor.IndicesPrivileges;
import org.elasticsearch.xpack.core.security.authz.accesscontrol.DocumentSubsetBitsetCache;
import org.elasticsearch.xpack.core.security.authz.permission.FieldPermissionsCache;
import org.elasticsearch.xpack.core.security.authz.permission.FieldPermissionsDefinition;
import org.elasticsearch.xpack.core.security.authz.permission.FieldPermissionsDefinition.FieldGrantExcludeGroup;
import org.elasticsearch.xpack.core.security.authz.permission.LimitedRole;
import org.elasticsearch.xpack.core.security.authz.permission.Role;
import org.elasticsearch.xpack.core.security.authz.privilege.ApplicationPrivilege;
import org.elasticsearch.xpack.core.security.authz.privilege.ConfigurableClusterPrivilege;
import org.elasticsearch.xpack.core.security.authz.privilege.IndexPrivilege;
import org.elasticsearch.xpack.core.security.authz.privilege.Privilege;
import org.elasticsearch.xpack.core.security.authz.store.ReservedRolesStore;
import org.elasticsearch.xpack.core.security.authz.store.RoleKey;
import org.elasticsearch.xpack.core.security.authz.store.RoleReference;
import org.elasticsearch.xpack.core.security.authz.store.RolesRetrievalResult;
import org.elasticsearch.xpack.core.security.support.CacheIteratorHelper;
import org.elasticsearch.xpack.core.security.user.AnonymousUser;
import org.elasticsearch.xpack.core.security.user.AsyncSearchUser;
import org.elasticsearch.xpack.core.security.user.SystemUser;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.core.security.user.XPackSecurityUser;
import org.elasticsearch.xpack.core.security.user.XPackUser;
import org.elasticsearch.xpack.security.authc.ApiKeyService;
import org.elasticsearch.xpack.security.authc.service.ServiceAccountService;
import org.elasticsearch.xpack.security.support.SecurityIndexManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.elasticsearch.common.util.set.Sets.newHashSet;
import static org.elasticsearch.xpack.security.support.SecurityIndexManager.isIndexDeleted;
import static org.elasticsearch.xpack.security.support.SecurityIndexManager.isMoveFromRedToNonRed;

/**
 * A composite roles store that can retrieve roles from multiple sources.
 * @see RoleProviders
 */
public class CompositeRolesStore {

    static final Setting<Integer> NEGATIVE_LOOKUP_CACHE_SIZE_SETTING = Setting.intSetting(
        "xpack.security.authz.store.roles.negative_lookup_cache.max_size",
        10000,
        Property.NodeScope
    );
    private static final Setting<Integer> CACHE_SIZE_SETTING = Setting.intSetting(
        "xpack.security.authz.store.roles.cache.max_size",
        10000,
        Property.NodeScope
    );
    private static final Logger logger = LogManager.getLogger(CompositeRolesStore.class);

    private final RoleProviders roleProviders;
    private final NativePrivilegeStore privilegeStore;
    private final FieldPermissionsCache fieldPermissionsCache;
    private final Cache<RoleKey, Role> roleCache;
    private final CacheIteratorHelper<RoleKey, Role> roleCacheHelper;
    private final Cache<String, Boolean> negativeLookupCache;
    private final DocumentSubsetBitsetCache dlsBitsetCache;
    private final AnonymousUser anonymousUser;
    private final AtomicLong numInvalidation = new AtomicLong();
    private final RoleDescriptorStore roleReferenceResolver;
    private final Role superuserRole;
    private final Role xpackSecurityRole;
    private final Role xpackUserRole;
    private final Role asyncSearchUserRole;
    private final Automaton restrictedIndicesAutomaton;

    public CompositeRolesStore(
        Settings settings,
        RoleProviders roleProviders,
        NativePrivilegeStore privilegeStore,
        ThreadContext threadContext,
        XPackLicenseState licenseState,
        FieldPermissionsCache fieldPermissionsCache,
        ApiKeyService apiKeyService,
        ServiceAccountService serviceAccountService,
        DocumentSubsetBitsetCache dlsBitsetCache,
        IndexNameExpressionResolver resolver,
        Consumer<Collection<RoleDescriptor>> effectiveRoleDescriptorsConsumer
    ) {
        this.roleProviders = roleProviders;
        roleProviders.addChangeListener(new RoleProviders.ChangeListener() {
            @Override
            public void rolesChanged(Set<String> roles) {
                CompositeRolesStore.this.invalidate(roles);
            }

            @Override
            public void providersChanged() {
                CompositeRolesStore.this.invalidateAll();
            }
        });

        this.privilegeStore = Objects.requireNonNull(privilegeStore);
        this.dlsBitsetCache = Objects.requireNonNull(dlsBitsetCache);
        this.fieldPermissionsCache = Objects.requireNonNull(fieldPermissionsCache);
        CacheBuilder<RoleKey, Role> builder = CacheBuilder.builder();
        final int cacheSize = CACHE_SIZE_SETTING.get(settings);
        if (cacheSize >= 0) {
            builder.setMaximumWeight(cacheSize);
        }
        this.roleCache = builder.build();
        this.roleCacheHelper = new CacheIteratorHelper<>(roleCache);
        CacheBuilder<String, Boolean> nlcBuilder = CacheBuilder.builder();
        final int nlcCacheSize = NEGATIVE_LOOKUP_CACHE_SIZE_SETTING.get(settings);
        if (nlcCacheSize >= 0) {
            nlcBuilder.setMaximumWeight(nlcCacheSize);
        }
        this.negativeLookupCache = nlcBuilder.build();
        this.restrictedIndicesAutomaton = resolver.getSystemNameAutomaton();
        this.superuserRole = Role.builder(ReservedRolesStore.SUPERUSER_ROLE_DESCRIPTOR, fieldPermissionsCache, restrictedIndicesAutomaton)
            .build();
        xpackSecurityRole = Role.builder(XPackSecurityUser.ROLE_DESCRIPTOR, fieldPermissionsCache, restrictedIndicesAutomaton).build();
        xpackUserRole = Role.builder(XPackUser.ROLE_DESCRIPTOR, fieldPermissionsCache, restrictedIndicesAutomaton).build();
        asyncSearchUserRole = Role.builder(AsyncSearchUser.ROLE_DESCRIPTOR, fieldPermissionsCache, restrictedIndicesAutomaton).build();

        this.roleReferenceResolver = new RoleDescriptorStore(
            roleProviders,
            apiKeyService,
            serviceAccountService,
            negativeLookupCache,
            licenseState,
            threadContext,
            effectiveRoleDescriptorsConsumer
        );
        this.anonymousUser = new AnonymousUser(settings);
    }

    public void getRoles(Authentication authentication, ActionListener<Tuple<Role, Role>> roleActionListener) {
        final AuthenticationContext authenticationContext = AuthenticationContext.fromAuthentication(authentication);
        getRole(authenticationContext.getEffectiveSubject(), ActionListener.wrap(role -> {
            if (authenticationContext.isRunAs()) {
                getRole(
                    authenticationContext.getAuthenticatingSubject(),
                    ActionListener.wrap(
                        authenticatingRole -> roleActionListener.onResponse(new Tuple<>(role, authenticatingRole)),
                        roleActionListener::onFailure
                    )
                );
            } else {
                roleActionListener.onResponse(new Tuple<>(role, role));
            }
        }, roleActionListener::onFailure));
    }

    public void getRole(Subject subject, ActionListener<Role> roleActionListener) {
        final Role internalUserRole = tryGetRoleForInternalUser(subject);
        if (internalUserRole != null) {
            roleActionListener.onResponse(internalUserRole);
            return;
        }

        assert false == User.isInternal(subject.getUser()) : "Internal user should not pass here";

        final List<RoleReference> roleReferences = subject.getRoleReferences(anonymousUser);
        // TODO: Two levels of nesting can be relaxed in future
        assert roleReferences.size() <= 2 : "only support up to one level of limiting";
        assert false == roleReferences.isEmpty() : "role references cannot be empty";

        buildRoleFromRoleReference(roleReferences.get(0), ActionListener.wrap(role -> {
            if (roleReferences.size() == 1) {
                roleActionListener.onResponse(role);
            } else {
                buildRoleFromRoleReference(
                    roleReferences.get(1),
                    ActionListener.wrap(
                        limitedByRole -> roleActionListener.onResponse(LimitedRole.createLimitedRole(role, limitedByRole)),
                        roleActionListener::onFailure
                    )
                );
            }

        }, roleActionListener::onFailure));
    }

    // Accessible by tests
    Role tryGetRoleForInternalUser(Subject subject) {
        // we need to special case the internal users in this method, if we apply the anonymous roles to every user including these system
        // user accounts then we run into the chance of a deadlock because then we need to get a role that we may be trying to get as the
        // internal user.
        // The SystemUser is special cased as it has special privileges to execute internal actions and should never be passed into this
        // method.
        // The other internal users have directly assigned roles that are handled with special cases here
        final User user = subject.getUser();
        if (SystemUser.is(user)) {
            throw new IllegalArgumentException(
                "the user [" + user.principal() + "] is the system user and we should never try to get its" + " roles"
            );
        }
        if (XPackUser.is(user)) {
            assert XPackUser.INSTANCE.roles().length == 1;
            return xpackUserRole;
        }
        if (XPackSecurityUser.is(user)) {
            return xpackSecurityRole;
        }
        if (AsyncSearchUser.is(user)) {
            return asyncSearchUserRole;
        }
        return null;
    }

    public void buildRoleFromRoleReference(RoleReference roleReference, ActionListener<Role> roleActionListener) {
        final RoleKey roleKey = roleReference.id();
        if (roleKey == RoleKey.ROLE_KEY_SUPERUSER) {
            roleActionListener.onResponse(superuserRole);
            return;
        }
        if (roleKey == RoleKey.ROLE_KEY_EMPTY) {
            roleActionListener.onResponse(Role.EMPTY);
            return;
        }

        final Role existing = roleCache.get(roleKey);
        if (existing == null) {
            final long invalidationCounter = numInvalidation.get();
            roleReference.resolve(roleReferenceResolver, ActionListener.wrap(rolesRetrievalResult -> {
                if (RolesRetrievalResult.EMPTY == rolesRetrievalResult) {
                    roleActionListener.onResponse(Role.EMPTY);
                } else if (RolesRetrievalResult.SUPERUSER == rolesRetrievalResult) {
                    roleActionListener.onResponse(superuserRole);
                } else {
                    buildThenMaybeCacheRole(
                        roleKey,
                        rolesRetrievalResult.getRoleDescriptors(),
                        rolesRetrievalResult.getMissingRoles(),
                        rolesRetrievalResult.isSuccess(),
                        invalidationCounter,
                        roleActionListener
                    );
                }
            }, e -> {
                // Because superuser does not have write access to restricted indices, it is valid to mix superuser with other roles to
                // gain addition access. However, if retrieving those roles fails for some reason, then that could leave admins in a
                // situation where they are unable to administer their cluster (in order to resolve the problem that is leading to failures
                // in role retrieval). So if a role reference includes superuser, but role retrieval failed, we fallback to the static
                // superuser role.
                if (includesSuperuserRole(roleReference)) {
                    logger.warn(
                        new ParameterizedMessage(
                            "there was a failure resolving the roles [{}], falling back to the [{}] role instead",
                            roleReference.id(),
                            Strings.arrayToCommaDelimitedString(superuserRole.names())
                        ),
                        e
                    );
                    roleActionListener.onResponse(superuserRole);
                } else {
                    roleActionListener.onFailure(e);
                }
            }));
        } else {
            roleActionListener.onResponse(existing);
        }
    }

    private boolean includesSuperuserRole(RoleReference roleReference) {
        if (roleReference instanceof RoleReference.NamedRoleReference namedRoles) {
            return Arrays.asList(namedRoles.getRoleNames()).contains(ReservedRolesStore.SUPERUSER_ROLE_DESCRIPTOR.getName());
        } else {
            return false;
        }
    }

    // package private for testing
    RoleDescriptorStore getRoleReferenceResolver() {
        return roleReferenceResolver;
    }

    // for testing
    Role getXpackUserRole() {
        return xpackUserRole;
    }

    // for testing
    Role getAsyncSearchUserRole() {
        return asyncSearchUserRole;
    }

    private void buildThenMaybeCacheRole(
        RoleKey roleKey,
        Collection<RoleDescriptor> roleDescriptors,
        Set<String> missing,
        boolean tryCache,
        long invalidationCounter,
        ActionListener<Role> listener
    ) {
        logger.trace(
            "Building role from descriptors [{}] for names [{}] from source [{}]",
            roleDescriptors,
            roleKey.getNames(),
            roleKey.getSource()
        );
        buildRoleFromDescriptors(
            roleDescriptors,
            fieldPermissionsCache,
            privilegeStore,
            restrictedIndicesAutomaton,
            ActionListener.wrap(role -> {
                if (role != null && tryCache) {
                    try (ReleasableLock ignored = roleCacheHelper.acquireUpdateLock()) {
                        /* this is kinda spooky. We use a read/write lock to ensure we don't modify the cache if we hold
                         * the write lock (fetching stats for instance - which is kinda overkill?) but since we fetching
                         * stuff in an async fashion we need to make sure that if the cache got invalidated since we
                         * started the request we don't put a potential stale result in the cache, hence the
                         * numInvalidation.get() comparison to the number of invalidation when we started. we just try to
                         * be on the safe side and don't cache potentially stale results
                         */
                        if (invalidationCounter == numInvalidation.get()) {
                            roleCache.computeIfAbsent(roleKey, (s) -> role);
                        }
                    }

                    for (String missingRole : missing) {
                        negativeLookupCache.computeIfAbsent(missingRole, s -> Boolean.TRUE);
                    }
                }
                listener.onResponse(role);
            }, listener::onFailure)
        );
    }

    // TODO: Temporary to fill the gap
    public void getRoleDescriptors(Set<String> roleNames, ActionListener<Set<RoleDescriptor>> listener) {
        roleReferenceResolver.getRoleDescriptors(roleNames, listener);
    }

    public static void buildRoleFromDescriptors(
        Collection<RoleDescriptor> roleDescriptors,
        FieldPermissionsCache fieldPermissionsCache,
        NativePrivilegeStore privilegeStore,
        Automaton restrictedIndicesAutomaton,
        ActionListener<Role> listener
    ) {
        if (roleDescriptors.isEmpty()) {
            listener.onResponse(Role.EMPTY);
            return;
        }

        Set<String> clusterPrivileges = new HashSet<>();
        final List<ConfigurableClusterPrivilege> configurableClusterPrivileges = new ArrayList<>();
        Set<String> runAs = new HashSet<>();
        final Map<Set<String>, MergeableIndicesPrivilege> restrictedIndicesPrivilegesMap = new HashMap<>();
        final Map<Set<String>, MergeableIndicesPrivilege> indicesPrivilegesMap = new HashMap<>();

        // Keyed by application + resource
        Map<Tuple<String, Set<String>>, Set<String>> applicationPrivilegesMap = new HashMap<>();

        List<String> roleNames = new ArrayList<>(roleDescriptors.size());
        for (RoleDescriptor descriptor : roleDescriptors) {
            roleNames.add(descriptor.getName());
            if (descriptor.getClusterPrivileges() != null) {
                clusterPrivileges.addAll(Arrays.asList(descriptor.getClusterPrivileges()));
            }
            if (descriptor.getConditionalClusterPrivileges() != null) {
                configurableClusterPrivileges.addAll(Arrays.asList(descriptor.getConditionalClusterPrivileges()));
            }
            if (descriptor.getRunAs() != null) {
                runAs.addAll(Arrays.asList(descriptor.getRunAs()));
            }
            MergeableIndicesPrivilege.collatePrivilegesByIndices(descriptor.getIndicesPrivileges(), true, restrictedIndicesPrivilegesMap);
            MergeableIndicesPrivilege.collatePrivilegesByIndices(descriptor.getIndicesPrivileges(), false, indicesPrivilegesMap);
            for (RoleDescriptor.ApplicationResourcePrivileges appPrivilege : descriptor.getApplicationPrivileges()) {
                Tuple<String, Set<String>> key = new Tuple<>(appPrivilege.getApplication(), newHashSet(appPrivilege.getResources()));
                applicationPrivilegesMap.compute(key, (k, v) -> {
                    if (v == null) {
                        return newHashSet(appPrivilege.getPrivileges());
                    } else {
                        v.addAll(Arrays.asList(appPrivilege.getPrivileges()));
                        return v;
                    }
                });
            }
        }

        final Privilege runAsPrivilege = runAs.isEmpty() ? Privilege.NONE : new Privilege(runAs, runAs.toArray(Strings.EMPTY_ARRAY));
        final Role.Builder builder = Role.builder(restrictedIndicesAutomaton, roleNames.toArray(Strings.EMPTY_ARRAY))
            .cluster(clusterPrivileges, configurableClusterPrivileges)
            .runAs(runAsPrivilege);
        indicesPrivilegesMap.forEach(
            (key, privilege) -> builder.add(
                fieldPermissionsCache.getFieldPermissions(privilege.fieldPermissionsDefinition),
                privilege.query,
                IndexPrivilege.get(privilege.privileges),
                false,
                privilege.indices.toArray(Strings.EMPTY_ARRAY)
            )
        );
        restrictedIndicesPrivilegesMap.forEach(
            (key, privilege) -> builder.add(
                fieldPermissionsCache.getFieldPermissions(privilege.fieldPermissionsDefinition),
                privilege.query,
                IndexPrivilege.get(privilege.privileges),
                true,
                privilege.indices.toArray(Strings.EMPTY_ARRAY)
            )
        );

        if (applicationPrivilegesMap.isEmpty()) {
            listener.onResponse(builder.build());
        } else {
            final Set<String> applicationNames = applicationPrivilegesMap.keySet().stream().map(Tuple::v1).collect(Collectors.toSet());
            final Set<String> applicationPrivilegeNames = applicationPrivilegesMap.values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
            privilegeStore.getPrivileges(applicationNames, applicationPrivilegeNames, ActionListener.wrap(appPrivileges -> {
                applicationPrivilegesMap.forEach(
                    (key, names) -> ApplicationPrivilege.get(key.v1(), names, appPrivileges)
                        .forEach(priv -> builder.addApplicationPrivilege(priv, key.v2()))
                );
                listener.onResponse(builder.build());
            }, listener::onFailure));
        }
    }

    public void invalidateAll() {
        numInvalidation.incrementAndGet();
        negativeLookupCache.invalidateAll();
        try (ReleasableLock ignored = roleCacheHelper.acquireUpdateLock()) {
            roleCache.invalidateAll();
        }
        dlsBitsetCache.clear("role store invalidation");
    }

    public void invalidate(String role) {
        numInvalidation.incrementAndGet();

        roleCacheHelper.removeKeysIf(key -> key.getNames().contains(role));
        negativeLookupCache.invalidate(role);
    }

    public void invalidate(Set<String> roles) {
        numInvalidation.incrementAndGet();
        roleCacheHelper.removeKeysIf(key -> Sets.haveEmptyIntersection(key.getNames(), roles) == false);
        roles.forEach(negativeLookupCache::invalidate);
    }

    public void usageStats(ActionListener<Map<String, Object>> listener) {
        final Map<String, Object> usage = new HashMap<>();
        usage.put("dls", Map.of("bit_set_cache", dlsBitsetCache.usageStats()));
        roleProviders.usageStats(listener.map(roleUsage -> {
            usage.putAll(roleUsage);
            return usage;
        }));
    }

    public void onSecurityIndexStateChange(SecurityIndexManager.State previousState, SecurityIndexManager.State currentState) {
        if (isMoveFromRedToNonRed(previousState, currentState)
            || isIndexDeleted(previousState, currentState)
            || Objects.equals(previousState.indexUUID, currentState.indexUUID) == false
            || previousState.isIndexUpToDate != currentState.isIndexUpToDate) {
            invalidateAll();
        }
    }

    // pkg - private for testing
    boolean isValueInNegativeLookupCache(String key) {
        return negativeLookupCache.get(key) != null;
    }

    /**
     * A mutable class that can be used to represent the combination of one or more {@link IndicesPrivileges}
     */
    private static class MergeableIndicesPrivilege {
        private final Set<String> indices;
        private final Set<String> privileges;
        private FieldPermissionsDefinition fieldPermissionsDefinition;
        private Set<BytesReference> query = null;

        MergeableIndicesPrivilege(
            String[] indices,
            String[] privileges,
            @Nullable String[] grantedFields,
            @Nullable String[] deniedFields,
            @Nullable BytesReference query
        ) {
            this.indices = newHashSet(Objects.requireNonNull(indices));
            this.privileges = newHashSet(Objects.requireNonNull(privileges));
            this.fieldPermissionsDefinition = new FieldPermissionsDefinition(grantedFields, deniedFields);
            if (query != null) {
                this.query = newHashSet(query);
            }
        }

        void merge(MergeableIndicesPrivilege other) {
            assert indices.equals(other.indices) : "index names must be equivalent in order to merge";
            Set<FieldGrantExcludeGroup> groups = new HashSet<>();
            groups.addAll(this.fieldPermissionsDefinition.getFieldGrantExcludeGroups());
            groups.addAll(other.fieldPermissionsDefinition.getFieldGrantExcludeGroups());
            this.fieldPermissionsDefinition = new FieldPermissionsDefinition(groups);
            this.privileges.addAll(other.privileges);

            if (this.query == null || other.query == null) {
                this.query = null;
            } else {
                this.query.addAll(other.query);
            }
        }

        private static void collatePrivilegesByIndices(
            IndicesPrivileges[] indicesPrivileges,
            boolean allowsRestrictedIndices,
            Map<Set<String>, MergeableIndicesPrivilege> indicesPrivilegesMap
        ) {
            for (final IndicesPrivileges indicesPrivilege : indicesPrivileges) {
                // if a index privilege is an explicit denial, then we treat it as non-existent since we skipped these in the past when
                // merging
                final boolean isExplicitDenial = indicesPrivileges.length == 1
                    && "none".equalsIgnoreCase(indicesPrivilege.getPrivileges()[0]);
                if (isExplicitDenial || (indicesPrivilege.allowRestrictedIndices() != allowsRestrictedIndices)) {
                    continue;
                }
                final Set<String> key = newHashSet(indicesPrivilege.getIndices());
                indicesPrivilegesMap.compute(key, (k, value) -> {
                    if (value == null) {
                        return new MergeableIndicesPrivilege(
                            indicesPrivilege.getIndices(),
                            indicesPrivilege.getPrivileges(),
                            indicesPrivilege.getGrantedFields(),
                            indicesPrivilege.getDeniedFields(),
                            indicesPrivilege.getQuery()
                        );
                    } else {
                        value.merge(
                            new MergeableIndicesPrivilege(
                                indicesPrivilege.getIndices(),
                                indicesPrivilege.getPrivileges(),
                                indicesPrivilege.getGrantedFields(),
                                indicesPrivilege.getDeniedFields(),
                                indicesPrivilege.getQuery()
                            )
                        );
                        return value;
                    }
                });
            }
        }
    }

    public static List<Setting<?>> getSettings() {
        return Arrays.asList(CACHE_SIZE_SETTING, NEGATIVE_LOOKUP_CACHE_SIZE_SETTING);
    }
}
