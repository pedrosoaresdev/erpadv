package br.com.erpadv.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

/**
 * Inicializa o Firebase Admin SDK.
 *
 * <ul>
 *   <li>Produção: credenciais da service account lidas de um arquivo montado em tempo de execução
 *       ({@code FIREBASE_CREDENTIALS_PATH}) ou Application Default Credentials. Nada é embutido na imagem.</li>
 *   <li>Desenvolvimento: Firebase Emulator Suite. O SDK detecta {@code FIRESTORE_EMULATOR_HOST} e
 *       {@code FIREBASE_AUTH_EMULATOR_HOST}; credenciais fictícias são usadas.</li>
 * </ul>
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public FirebaseApp firebaseApp(AppProperties properties) throws IOException {
        AppProperties.Firebase fb = properties.firebase();
        FirebaseOptions.Builder builder = FirebaseOptions.builder()
                .setProjectId(fb.projectId())
                .setCredentials(resolveCredentials(fb));
        if (StringUtils.hasText(fb.storageBucket())) {
            builder.setStorageBucket(fb.storageBucket());
        }
        FirebaseApp app = FirebaseApp.getApps().stream()
                .filter(a -> FirebaseApp.DEFAULT_APP_NAME.equals(a.getName()))
                .findFirst()
                .orElseGet(() -> FirebaseApp.initializeApp(builder.build()));
        log.info("Firebase inicializado: projeto={}, emulador={}", fb.projectId(), fb.emulator());
        return app;
    }

    @Bean
    public FirebaseAuth firebaseAuth(FirebaseApp app) {
        return FirebaseAuth.getInstance(app);
    }

    @Bean
    public Firestore firestore(FirebaseApp app) {
        return FirestoreClient.getFirestore(app);
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp app) {
        return FirebaseMessaging.getInstance(app);
    }

    private GoogleCredentials resolveCredentials(AppProperties.Firebase fb) throws IOException {
        if (fb.emulator()) {
            // Os emuladores não validam credenciais; o token "owner" dá acesso administrativo local.
            return GoogleCredentials.create(new AccessToken("owner", new Date(Long.MAX_VALUE)));
        }
        if (StringUtils.hasText(fb.credentialsPath())) {
            try (InputStream in = new FileInputStream(fb.credentialsPath())) {
                return GoogleCredentials.fromStream(in);
            }
        }
        return GoogleCredentials.getApplicationDefault();
    }
}
