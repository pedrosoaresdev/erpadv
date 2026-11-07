package br.com.erpadv.firebase;

import br.com.erpadv.config.AppProperties;
import br.com.erpadv.exceptions.DataAccessFailure;
import com.google.cloud.NoCredentials;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.StorageClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Acesso ao Firebase Storage exclusivamente pelo backend. As Security Rules negam qualquer acesso
 * direto do navegador; downloads passam pela API (autenticados e auditados).
 * Inicialização preguiçosa: a API sobe mesmo sem bucket configurado.
 */
@Component
public class StorageGateway {

    private final FirebaseApp app;
    private final AppProperties properties;
    private volatile Storage storage;
    private volatile String bucket;

    public StorageGateway(FirebaseApp app, AppProperties properties) {
        this.app = app;
        this.properties = properties;
    }

    public void put(String path, byte[] bytes, String contentType, Map<String, String> metadata) {
        try {
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket(), path))
                    .setContentType(contentType)
                    .setMetadata(metadata)
                    .build();
            // Upload resumable: compatível com o Firebase Storage real e com o Emulator Suite.
            try (WriteChannel writer = storage().writer(info)) {
                writer.write(ByteBuffer.wrap(bytes));
            }
        } catch (StorageException | IOException e) {
            throw new DataAccessFailure("Falha ao armazenar o arquivo.", e);
        }
    }

    public byte[] get(String path) {
        try {
            Blob blob = storage().get(BlobId.of(bucket(), path));
            if (blob == null) {
                throw new br.com.erpadv.exceptions.NotFoundException("Arquivo");
            }
            return blob.getContent();
        } catch (StorageException e) {
            throw new DataAccessFailure("Falha ao ler o arquivo.", e);
        }
    }

    public void delete(String path) {
        try {
            storage().delete(BlobId.of(bucket(), path));
        } catch (StorageException e) {
            throw new DataAccessFailure("Falha ao remover o arquivo.", e);
        }
    }

    private String bucket() {
        if (bucket == null) {
            String configured = properties.firebase().storageBucket();
            bucket = configured == null || configured.isBlank()
                    ? properties.firebase().projectId() + ".appspot.com"
                    : configured;
        }
        return bucket;
    }

    private Storage storage() {
        if (storage == null) {
            synchronized (this) {
                if (storage == null) {
                    String emulatorHost = System.getenv("FIREBASE_STORAGE_EMULATOR_HOST");
                    if (emulatorHost != null && !emulatorHost.isBlank()) {
                        storage = StorageOptions.newBuilder()
                                .setHost("http://" + emulatorHost)
                                .setProjectId(properties.firebase().projectId())
                                .setCredentials(NoCredentials.getInstance())
                                .build()
                                .getService();
                    } else {
                        storage = StorageClient.getInstance(app).bucket(bucket()).getStorage();
                    }
                }
            }
        }
        return storage;
    }
}
