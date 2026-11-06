package br.com.erpadv.security;

import br.com.erpadv.config.AppProperties;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache curto do perfil {@code users/{uid}} usado a cada requisição autenticada (status, revogação de
 * sessão e permissões). Evita uma leitura do Firestore por request. Alterações de perfil feitas
 * pelo próprio backend invalidam a entrada imediatamente.
 */
@Component
public class UserProfileCache {

    private record Entry(Optional<UserProfile> profile, Instant expiresAt) {
    }

    private static final int MAX_ENTRIES = 10_000;

    private final UserRepository users;
    private final Duration ttl;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public UserProfileCache(UserRepository users, AppProperties properties) {
        this.users = users;
        this.ttl = Duration.ofSeconds(Math.max(1, properties.security().profileCacheSeconds()));
    }

    public Optional<UserProfile> get(String uid) {
        Instant now = Instant.now();
        Entry entry = entries.get(uid);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.profile();
        }
        Optional<UserProfile> loaded = users.findById(uid);
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        entries.put(uid, new Entry(loaded, now.plus(ttl)));
        return loaded;
    }

    public void evict(String uid) {
        entries.remove(uid);
    }
}
