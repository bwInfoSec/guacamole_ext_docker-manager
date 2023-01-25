package bwinfosec.de.guacamole;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.net.event.listener.Listener;
import org.apache.guacamole.protocol.GuacamoleConfiguration;
import org.apache.guacamole.properties.IntegerGuacamoleProperty;
import org.apache.guacamole.properties.StringGuacamoleProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

public class DockerManagerListener implements Listener {

    private static final Logger logger = LoggerFactory.getLogger(DockerManagerListener.class);

    private static final StringGuacamoleProperty DOCKER_ADDITIONAL_ENV = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "docker-extension-additional-env";
        }
    };

    private static final StringGuacamoleProperty DOCKER_CERT = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "docker-extension-cert";
        }
    };

    private static final IntegerGuacamoleProperty DOCKER_EXPOSED_PORT = new IntegerGuacamoleProperty() {

        @Override
        public String getName() {
            return "docker-extension-exposed-port";
        }
    };

    private static final StringGuacamoleProperty DOCKER_IMAGE = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "docker-extension-image";
        }
    };

    private static final String DEFAULT_DOCKER_IMAGE = "";

    private static final int DEFAULT_DOCKER_EXPOSED_PORT = 3389;

    private static final String DEFAULT_DOCKER_CERT = "/etc/guacamole/certs";

    private static final String DEFAULT_DOCKER_ADDITIONAL_ENV = "";

    private class createDockerContainerThreaded implements Runnable {

        private final String hostname;
        private final String port;
        private final String username;
        private final String password;
        private final String cert;
        private final int exposedPort;
        private final String image;
        private final String additionalEnv;

        public createDockerContainerThreaded(final String hostname,
                final String port,
                final String username,
                final String password,
                final String cert,
                final int exposedPort,
                final String additionalEnv,
                final String image) {
            this.hostname = hostname;
            this.exposedPort = exposedPort;
            this.port = port;
            this.username = username;
            this.password = password;
            this.cert = cert;
            this.additionalEnv = additionalEnv;
            this.image = image;
        }

        public void run() {

            final DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost(String.format("tcp://%s:2376", this.hostname))
                    .withDockerCertPath(this.cert) // https://stackoverflow.com/questions/40134311/how-to-connect-to-a-docker-daemon-listening-to-ssl-tls-connections-over-hostvmi
                    .withDockerTlsVerify(true)
                    .build();

            final DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();

            final DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);

            final List<Container> containers = dockerClient.listContainersCmd().exec();

            Boolean containerExists = false;

            for (final Container container : containers) {
                List<String> names = Arrays.asList(container.getNames());
                if (names.contains(String.format("/term_%s", this.username))) { // WHY THE FUCK /
                    containerExists = true;
                    break;
                }
            }

            if (!containerExists) {

                final ExposedPort userRDP = ExposedPort.tcp(this.exposedPort);
                final Ports portBindings = new Ports();
                portBindings.bind(userRDP, Ports.Binding.bindPort(Integer.parseInt(this.port)));

                // Get the additional environment variables by splitting
                List<String> envs = new ArrayList<String>();
                String[] additionalEnvArray = this.additionalEnv.split(",");
                envs.add(String.format("GUACAMOLE_USERNAME=%s", this.username));
                envs.add(String.format("GUACAMOLE_PASSWORD=%s", this.password));
                for (final String env : additionalEnvArray) {
                    envs.add(env);
                }

                CreateContainerResponse container = dockerClient
                        .createContainerCmd(this.image)
                        .withHostConfig(new HostConfig()
                                .withPortBindings(portBindings))
                        .withEnv(envs)
                        .withName(String.format("term_%s", this.username))
                        .exec();
            }
            dockerClient.startContainerCmd(container.getId()).exec();
        };
    }

    @Override
    public void handleEvent(Object event) throws GuacamoleException {
        if (event instanceof AuthenticationSuccessEvent) {

            AuthenticatedUser user = ((AuthenticationSuccessEvent) event).getAuthenticatedUser();

            UserContext context = user.getAuthenticationProvider().getUserContext(user);
            Set<String> identifiers = context.getConnectionDirectory().getIdentifiers();

            for (String identifier : identifiers) {
                logger.debug("Get Guacamole Configuration");
                final GuacamoleConfiguration config = context.getConnectionDirectory().get(identifier)
                        .getConfiguration();

                final String username = user.getCredentials().getUsername();
                final String password = user.getCredentials().getPassword();

                final String port = config.getParameter("port");
                final String hostname = config.getParameter("hostname");

                logger.info("Starting thread with: {}", config.getParameters().toString());

                Environment environment = LocalEnvironment.getInstance();

                Thread dockerCreateThread = new Thread(new createDockerContainerThreaded(hostname, port, username,
                        password, environment.getProperty(DOCKER_CERT, DEFAULT_DOCKER_CERT),
                        environment.getProperty(DOCKER_EXPOSED_PORT, DEFAULT_DOCKER_EXPOSED_PORT),
                        environment.getProperty(DOCKER_ADDITIONAL_ENV, DEFAULT_DOCKER_ADDITIONAL_ENV),
                        environment.getProperty(DOCKER_IMAGE, DEFAULT_DOCKER_IMAGE)));
                dockerCreateThread.start();
            }

        }

        logger.info("Received Guacamole event notification");
    }

}
