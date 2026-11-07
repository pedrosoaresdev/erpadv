package br.com.erpadv.security;

import br.com.erpadv.exceptions.ProblemFactory;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Valida o ID token do Firebase (JWT) enviado em {@code Authorization: Bearer} e monta o
 * {@link AuthenticatedUser}. Rejeita contas bloqueadas, sessões revogadas e tokens cujo escritório
 * não confere com o perfil persistido.
 */
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);
    public static final String AUTHORITY_STAFF = "STAFF";
    public static final String AUTHORITY_PENDING = "PENDING_ONBOARDING";

    private final FirebaseAuth firebaseAuth;
    private final UserProfileCache profiles;
    private final ProblemFactory problems;

    public FirebaseAuthenticationFilter(FirebaseAuth firebaseAuth, UserProfileCache profiles, ProblemFactory problems) {
        this.firebaseAuth = firebaseAuth;
        this.profiles = profiles;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }
        String idToken = header.substring(7).trim();
        FirebaseToken token;
        try {
            token = firebaseAuth.verifyIdToken(idToken);
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            log.info("Token rejeitado: {}", e.getMessage());
            problems.write(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Sessão inválida ou expirada.");
            return;
        }

        Map<String, Object> claims = token.getClaims();
        String officeId = claims.get("officeId") instanceof String s ? s : null;
        Role role = Role.parse(claims.get("role"));
        String clientId = claims.get("clientId") instanceof String s ? s : null;
        Set<Permission> permissions = Set.of();
        String displayName = token.getName();

        if (officeId != null && role != null) {
            Optional<UserProfile> profileOpt = profiles.get(token.getUid());
            if (profileOpt.isEmpty() || !officeId.equals(profileOpt.get().getOfficeId())) {
                problems.write(response, HttpStatus.UNAUTHORIZED, "PROFILE_MISMATCH",
                        "Perfil de acesso não encontrado. Entre novamente.");
                return;
            }
            UserProfile profile = profileOpt.get();
            if (profile.getStatus() == UserStatus.BLOCKED) {
                problems.write(response, HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "Conta bloqueada. Procure o administrador.");
                return;
            }
            if (isRevoked(claims, profile)) {
                problems.write(response, HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "Sessão encerrada pelo administrador.");
                return;
            }
            // O papel vigente é o do perfil (claims podem estar defasados até a renovação do token).
            role = profile.getRole() != null ? profile.getRole() : role;
            permissions = RolePermissions.effective(role, profile.getPermissionsGranted(), profile.getPermissionsRevoked());
            if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
                displayName = profile.getDisplayName();
            }
        }

        AuthenticatedUser user = new AuthenticatedUser(
                token.getUid(), token.getEmail(), displayName, token.isEmailVerified(),
                officeId, role, clientId, permissions);

        List<GrantedAuthority> authorities = new ArrayList<>();
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.authority())));
        if (role != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            if (role.isStaff()) {
                authorities.add(new SimpleGrantedAuthority(AUTHORITY_STAFF));
            }
        } else {
            authorities.add(new SimpleGrantedAuthority(AUTHORITY_PENDING));
        }

        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MDC.put("uid", user.uid());
        if (officeId != null) {
            MDC.put("officeId", officeId);
        }
        chain.doFilter(request, response);
    }

    private boolean isRevoked(Map<String, Object> claims, UserProfile profile) {
        if (profile.getTokensRevokedAt() == null) {
            return false;
        }
        Object iat = claims.get("iat");
        if (!(iat instanceof Number n)) {
            return true;
        }
        return n.longValue() * 1000L < profile.getTokensRevokedAt().toEpochMilli();
    }
}
