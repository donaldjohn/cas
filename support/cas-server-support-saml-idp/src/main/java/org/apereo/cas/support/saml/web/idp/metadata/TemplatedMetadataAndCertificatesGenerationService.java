package org.apereo.cas.support.saml.web.idp.metadata;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.shibboleth.utilities.java.support.security.SelfSignedCertificateGenerator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.support.saml.idp.SamlIdPProperties;
import org.apereo.cas.util.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A metadata generator based on a predefined template.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Slf4j
public class TemplatedMetadataAndCertificatesGenerationService implements SamlIdpMetadataAndCertificatesGenerationService {
    private static final String URI_SUBJECT_ALTNAME_POSTFIX = "/idp/metadata";

    private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
    private static final String END_CERTIFICATE = "-----END CERTIFICATE-----";


    @Autowired
    private CasConfigurationProperties casProperties;

    @Autowired
    private ResourceLoader resourceLoader;

    /**
     * Initializes a new Generate saml metadata.
     */
    @PostConstruct
    @SneakyThrows
    public void initialize() {
        final SamlIdPProperties idp = casProperties.getAuthn().getSamlIdp();
        final Resource metadataLocation = idp.getMetadata().getLocation();

        if (!metadataLocation.exists()) {
            LOGGER.debug("Metadata directory [{}] does not exist. Creating...", metadataLocation);
            if (!metadataLocation.getFile().mkdir()) {
                throw new IllegalArgumentException("Metadata directory location " + metadataLocation + " cannot be located/created");
            }
        }
        LOGGER.info("Metadata directory location is at [{}] with entityID [{}]", metadataLocation, idp.getEntityId());

        performGenerationSteps();
    }

    /**
     * Is metadata missing?
     *
     * @return true/false
     */
    @SneakyThrows
    public boolean isMetadataMissing() {
        final SamlIdPProperties idp = casProperties.getAuthn().getSamlIdp();
        return !idp.getMetadata().getMetadataFile().exists();
    }

    @Override
    @SneakyThrows
    public File performGenerationSteps() {
        final SamlIdPProperties idp = casProperties.getAuthn().getSamlIdp();
        LOGGER.debug("Preparing to generate metadata for entityId [{}]", idp.getEntityId());
        if (isMetadataMissing()) {
            LOGGER.info("Metadata does not exist at [{}]. Creating...", idp.getMetadata().getMetadataFile());

            LOGGER.info("Creating self-sign certificate for signing...");
            buildSelfSignedSigningCert();

            LOGGER.info("Creating self-sign certificate for encryption...");
            buildSelfSignedEncryptionCert();

            LOGGER.info("Creating metadata...");
            buildMetadataGeneratorParameters();
        }

        LOGGER.info("Metadata is available at [{}]", idp.getMetadata().getMetadataFile());

        return idp.getMetadata().getMetadataFile();
    }

    private String getIdPEndpointUrl() {
        return casProperties.getServer().getPrefix().concat("/idp");
    }

    @SneakyThrows
    private String getIdPHostName() {
        final URL url = new URL(casProperties.getServer().getPrefix());
        return url.getHost();
    }

    /**
     * Build self signed encryption cert.
     *
     * @throws Exception the exception
     */
    protected void buildSelfSignedEncryptionCert() throws Exception {
        final SamlIdPProperties idp = casProperties.getAuthn().getSamlIdp();
        final SelfSignedCertificateGenerator generator = new SelfSignedCertificateGenerator();
        generator.setHostName(getIdPHostName());
        final File encCert = idp.getMetadata().getEncryptionCertFile().getFile();
        if (encCert.exists()) {
            FileUtils.forceDelete(encCert);
        }
        generator.setCertificateFile(encCert);
        final File encKey = idp.getMetadata().getEncryptionKeyFile().getFile();
        if (encKey.exists()) {
            FileUtils.forceDelete(encKey);
        }
        generator.setPrivateKeyFile(encKey);
        generator.setURISubjectAltNames(CollectionUtils.wrap(getIdPHostName().concat(URI_SUBJECT_ALTNAME_POSTFIX)));
        generator.generate();
    }

    /**
     * Build self signed signing cert.
     *
     * @throws Exception the exception
     */
    protected void buildSelfSignedSigningCert() throws Exception {
        final SamlIdPProperties idp = casProperties.getAuthn().getSamlIdp();
        final SelfSignedCertificateGenerator generator = new SelfSignedCertificateGenerator();
        generator.setHostName(getIdPHostName());
        final File signingCert = idp.getMetadata().getSigningCertFile().getFile();
        if (signingCert.exists()) {
            FileUtils.forceDelete(signingCert);
        }

        generator.setCertificateFile(signingCert);
        final File signingKey = idp.getMetadata().getSigningKeyFile().getFile();
        if (signingKey.exists()) {
            FileUtils.forceDelete(signingKey);
        }
        generator.setPrivateKeyFile(signingKey);
        generator.setURISubjectAltNames(CollectionUtils.wrap(getIdPHostName().concat(URI_SUBJECT_ALTNAME_POSTFIX)));
        generator.generate();
    }

    /**
     * Build metadata generator parameters by passing the encryption,
     * signing and back-channel certs to the parameter generator.
     *
     * @throws Exception Thrown if cert files are missing, or metadata file inaccessible.
     */
    protected void buildMetadataGeneratorParameters() throws Exception {
        final SamlIdPProperties idp = casProperties.getAuthn().getSamlIdp();
        final Resource template = this.resourceLoader.getResource("classpath:/template-idp-metadata.xml");

        String signingKey = FileUtils.readFileToString(idp.getMetadata().getSigningCertFile().getFile(), StandardCharsets.UTF_8);
        signingKey = StringUtils.remove(signingKey, BEGIN_CERTIFICATE);
        signingKey = StringUtils.remove(signingKey, END_CERTIFICATE).trim();

        String encryptionKey = FileUtils.readFileToString(idp.getMetadata().getEncryptionCertFile().getFile(), StandardCharsets.UTF_8);
        encryptionKey = StringUtils.remove(encryptionKey, BEGIN_CERTIFICATE);
        encryptionKey = StringUtils.remove(encryptionKey, END_CERTIFICATE).trim();

        try (StringWriter writer = new StringWriter()) {
            IOUtils.copy(template.getInputStream(), writer, StandardCharsets.UTF_8);
            final String metadata = writer.toString()
                .replace("${entityId}", idp.getEntityId())
                .replace("${scope}", idp.getScope())
                .replace("${idpEndpointUrl}", getIdPEndpointUrl())
                .replace("${encryptionKey}", encryptionKey)
                .replace("${signingKey}", signingKey);
            FileUtils.write(idp.getMetadata().getMetadataFile(), metadata, StandardCharsets.UTF_8);
        }
    }
}
