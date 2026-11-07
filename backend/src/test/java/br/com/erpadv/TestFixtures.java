package br.com.erpadv;

import br.com.erpadv.config.AppProperties;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Role;
import br.com.erpadv.security.RolePermissions;

import java.util.List;
import java.util.Set;

public final class TestFixtures {

    public static final String ENC_KEY = "ZGV2LW9ubHkta2V5LWNoYW5nZS1tZS0zMi1ieXRlcyE=";
    public static final String HMAC_KEY = "ZGV2LW9ubHktaG1hYy1rZXktY2hhbmdlLW1lLTMyYnl0ZXM=";

    private TestFixtures() {
    }

    public static AppProperties properties(boolean production) {
        return new AppProperties(
                new AppProperties.Firebase("demo-erpadv", null, null, true, null),
                new AppProperties.Security(ENC_KEY, HMAC_KEY, 300, 60, false, production),
                new AppProperties.Cors(List.of("http://localhost:4200")),
                new AppProperties.Platform(List.of("dev@erpadv.test")));
    }

    public static AuthenticatedUser user(String uid, String officeId, Role role) {
        return new AuthenticatedUser(uid, uid + "@teste.com", "Usuário " + uid, true, officeId, role, null,
                RolePermissions.defaultsFor(role));
    }

    public static AuthenticatedUser user(String uid, String officeId, Role role, Set<br.com.erpadv.security.Permission> perms) {
        return new AuthenticatedUser(uid, uid + "@teste.com", "Usuário " + uid, true, officeId, role, null, perms);
    }
}
