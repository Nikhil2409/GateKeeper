package in.code.gatekeeper.management.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * The RS256 key pair tokens are signed with. Loaded from the configured PEM files; with generate-if-missing (Docker),
 * a fresh pair is created on the first start and written to those files (a volume), so a clone works without
 * anyone handling a private key.
 */
@Slf4j
@Component
public class JwtKeys {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public JwtKeys(ResourceLoader resourceLoader,
                   @Value("${gatekeeper.security.jwt.public-key}") String publicKeyLocation,
                   @Value("${gatekeeper.security.jwt.private-key}") String privateKeyLocation,
                   @Value("${gatekeeper.security.jwt.generate-if-missing:false}") boolean generateIfMissing) {
        Resource publicResource = resourceLoader.getResource(publicKeyLocation);
        Resource privateResource = resourceLoader.getResource(privateKeyLocation);
        if (!publicResource.exists() || !privateResource.exists()) {
            if (!generateIfMissing) {
                throw new IllegalStateException("JWT keys not found at " + publicKeyLocation + " / " + privateKeyLocation);
            }
            generate(publicResource, privateResource);
        }
        try (InputStream publicPem = publicResource.getInputStream(); InputStream privatePem = privateResource.getInputStream()) {
            this.publicKey = RsaKeyConverters.x509().convert(publicPem);
            this.privateKey = RsaKeyConverters.pkcs8().convert(privatePem);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the JWT keys", e);
        }
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    private static void generate(Resource publicResource, Resource privateResource) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            write(publicResource.getFile().toPath(), "PUBLIC KEY", pair.getPublic().getEncoded(), "rw-r--r--");
            // owner-only: the volume is also mounted into Grafana, which may read only the public key
            write(privateResource.getFile().toPath(), "PRIVATE KEY", pair.getPrivate().getEncoded(), "rw-------");
            log.warn("No JWT keys found: generated a new RSA key pair at {}", publicResource.getFile().getParent());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is always available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write generated JWT keys (generate-if-missing needs file: locations)", e);
        }
    }

    private static void write(Path path, String type, byte[] der, String permissions) throws IOException {
        Files.createDirectories(path.getParent());
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }
}
