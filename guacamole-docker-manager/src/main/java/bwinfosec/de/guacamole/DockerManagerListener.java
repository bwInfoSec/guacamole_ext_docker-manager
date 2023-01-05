package bwinfosec.de.guacamole;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.net.event.listener.Listener;
import org.apache.guacamole.protocol.GuacamoleConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * TODO: Listen for authentication
 *
 */
public class DockerManagerListener implements Listener {

    private static final Logger logger = LoggerFactory.getLogger(DockerManagerListener.class);

    class createDockerContainerThreaded implements Runnable {

        String hostname;
        String port;
        String identifier;
        String username;
        String password;
        String hiscout_url;

        createDockerContainerThreaded(final String hostname, final String port, final String username, final String password) {
            this.hostname = hostname;
            this.port = port;
            this.username = username;
            this.password = password;
            this.hiscout_url = "https://hiscout-test.ismsdev.realm.bwinfosec.uni-heidelberg.de/"; // CHANGEME
        }

        public void run() {

            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost(String.format("tcp://%s:2376", hostname))
                    .withDockerCertPath("/etc/guacamole/certs") //https://stackoverflow.com/questions/40134311/how-to-connect-to-a-docker-daemon-listening-to-ssl-tls-connections-over-hostvmi
                    .withDockerTlsVerify(true)
                    .build();

            DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();

            DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);

            List<Container> containers = dockerClient.listContainersCmd().exec();

            Boolean containerExists = false;

            for (Container container : containers) {
                List<String> names = Arrays.asList(container.getNames());
                if (names.contains(String.format("/term_%s", username))) { // WHY THE FUCK /
                    containerExists = true;
                }
            }

            if (!containerExists) {

                ExposedPort userRDP = ExposedPort.tcp(3389);
                Ports portBindings = new Ports();
                portBindings.bind(userRDP, Ports.Binding.bindPort(Integer.parseInt(port)));

                CreateContainerResponse container = dockerClient
                        .createContainerCmd("registry-gitlab.urz.uni-heidelberg.de/it-sec/molecule-docker-images/production/ubuntu:rdesktop-ubuntu-kde")
                        .withHostConfig(new HostConfig()
                                .withPortBindings(portBindings))
                        .withEnv(String.format("GUACAMOLE_USERNAME=%s", username),
                                String.format("GUACAMOLE_PASSWORD=%s", password),
                                String.format("HISCOUT_URL=%s", hiscout_url))
                        .withName(String.format("term_%s", username))
                        .exec();

                dockerClient.startContainerCmd(container.getId()).exec();
            }
        };
    }

    @Override
    public void handleEvent(Object event) throws GuacamoleException {
        if (event instanceof AuthenticationSuccessEvent) {

            AuthenticatedUser user = ((AuthenticationSuccessEvent) event).getAuthenticatedUser();

            String username = user.getCredentials().getUsername();
            String password = user.getCredentials().getPassword();

            UserContext context = user.getAuthenticationProvider().getUserContext(user);
            Set<String> identifiers = context.getConnectionDirectory().getIdentifiers();

            for (String identifier : identifiers) {
                GuacamoleConfiguration config = context.getConnectionDirectory().get(identifier).getConfiguration();
                String port = config.getParameter("port");
                String hostname = config.getParameter("hostname");

                logger.info("STARTING THREAD WITH: {}",
                    config.getParameters().toString());
    
                Thread dockerCreateThread = new Thread(new createDockerContainerThreaded(hostname, port, username, password));
                dockerCreateThread.start();
            }

            logger.info("successful authentication for user {}",
                    ((AuthenticationSuccessEvent) event)
                            .getCredentials().getPassword());

        }

        logger.info("received Guacamole event notification");
    }

}
