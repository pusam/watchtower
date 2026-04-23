package com.watchtower.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Translates OIDC user info into Watchtower roles. The configured {@code roleClaim}
 * is read from id-token / userinfo claims and each value is mapped through
 * {@code roleMapping}; if nothing matches the user gets {@code defaultRole}
 * (or is denied when {@code defaultRole} is empty).
 */
public class WatchtowerOidcUserService extends OidcUserService {

    private final MonitorProperties.Oidc config;

    public WatchtowerOidcUserService(MonitorProperties.Oidc config) {
        this.config = config;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser delegate = super.loadUser(userRequest);

        Set<String> groups = extractGroups(delegate.getClaims());
        Set<String> mapped = new LinkedHashSet<>();
        for (String g : groups) {
            String mappedRole = config.getRoleMapping().get(g);
            if (mappedRole != null && !mappedRole.isBlank()) mapped.add(mappedRole.toUpperCase());
        }
        if (mapped.isEmpty() && config.getDefaultRole() != null && !config.getDefaultRole().isBlank()) {
            mapped.add(config.getDefaultRole().toUpperCase());
        }
        if (mapped.isEmpty()) {
            throw new OAuth2AuthenticationException("user has no mapped Watchtower role");
        }

        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : mapped) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        authorities.add(new SimpleGrantedAuthority("SCOPE_openid"));

        String nameAttr = pickUsernameAttribute(delegate.getClaims());
        return new DefaultOidcUser(authorities, delegate.getIdToken(), delegate.getUserInfo(), nameAttr);
    }

    private String pickUsernameAttribute(Map<String, Object> claims) {
        String preferred = config.getUsernameClaim();
        if (preferred != null && !preferred.isBlank() && claims.containsKey(preferred)) return preferred;
        return "sub";
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractGroups(Map<String, Object> claims) {
        Object raw = claims.get(config.getRoleClaim());
        if (raw == null) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) if (o != null) out.add(o.toString());
        } else if (raw instanceof String s) {
            for (String part : s.split("[\\s,]+")) if (!part.isBlank()) out.add(part);
        } else {
            out.add(raw.toString());
        }
        return out;
    }
}
