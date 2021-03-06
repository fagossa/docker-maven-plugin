package io.fabric8.maven.docker.assembly;

import java.io.File;
import java.io.IOException;
import java.util.*;

import com.google.common.base.Joiner;
import io.fabric8.maven.docker.config.Arguments;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

/**
 * Create a dockerfile
 *
 * @author roland
 * @since 17.04.14
 */
public class DockerFileBuilder {

    private static final Joiner JOIN_ON_COMMA = Joiner.on("\",\"");

    // Base image to use as from
    private String baseImage;

    // Maintainer of this image
    private String maintainer;

    // Workdir
    private String workdir = null;

    // Basedir to be export
    private String basedir = "/maven";

    private Arguments entryPoint;
    private Arguments cmd;

    private Boolean exportBasedir = null;

    // User under which the files should be added
    private String assemblyUser;

    // User to run as
    private String user;

    // List of files to add. Source and destination follow except that destination
    // in interpreted as a relative path to the exportDir
    // See also http://docs.docker.io/reference/builder/#add
    private List<AddEntry> addEntries = new ArrayList<>();

    // list of ports to expose and environments to use
    private List<Integer> ports = new ArrayList<>();

    // list of RUN Commands to run along with image build see issue #191 on github
    private List<String> runCmds = new ArrayList<>();

    // environment
    private Map<String,String> envEntries = new HashMap<>();

    // image labels
    private Map<String, String> labels = new HashMap<>();

    // exposed volumes
    private List<String> volumes = new ArrayList<>();

    // whether the Dockerfile should be optimised. i.e. compressing run statements into a single statement
    private boolean shouldOptimise = false;

    /**
     * Create a DockerFile in the given directory
     * @param  destDir directory where to store the dockerfile
     * @return the full path to the docker file
     * @throws IOException if writing fails
     */
    public File write(File destDir) throws IOException {
        File target = new File(destDir,"Dockerfile");
        FileUtils.fileWrite(target, content());
        return target;
    }

    /**
     * Create a Dockerfile following the format described in the
     * <a href="http://docs.docker.io/reference/builder/#usage">Docker reference manual</a>
     *
     * @return the dockerfile create
     * @throws IllegalArgumentException if no src/dest entries have been added
     */
    public String content() throws IllegalArgumentException {

        StringBuilder b = new StringBuilder();

        DockerFileKeyword.FROM.addTo(b, baseImage != null ? baseImage : DockerAssemblyManager.DEFAULT_DATA_BASE_IMAGE);
        if (maintainer != null) {
            DockerFileKeyword.MAINTAINER.addTo(b, maintainer);
        }

        addOptimisation();
        addEnv(b);
        addLabels(b);
        addPorts(b);

        addEntries(b);
        addWorkdir(b);
        addRun(b);
        addVolumes(b);

        addCmd(b);
        addEntryPoint(b);

        addUser(b);

        return b.toString();
    }

    private void addUser(StringBuilder b) {
        if (user != null) {
            DockerFileKeyword.USER.addTo(b, user);
        }
    }

    private void addWorkdir(StringBuilder b) {
        if (workdir != null) {
            DockerFileKeyword.WORKDIR.addTo(b, workdir);
        }
    }

    private void addEntryPoint(StringBuilder b){
        if (entryPoint != null) {
            buildArguments(b, DockerFileKeyword.ENTRYPOINT, entryPoint);
        }
    }

    private void addCmd(StringBuilder b){
        if (cmd != null) {
            buildArguments(b, DockerFileKeyword.CMD, cmd);
        }
    }

    private static void buildArguments(StringBuilder b, DockerFileKeyword key, Arguments arguments) {
        String arg;
        if (arguments.getShell() != null) {
            arg = arguments.getShell();
        } else {
            arg = "[\""  + JOIN_ON_COMMA.join(arguments.getExec()) + "\"]";
        }
        key.addTo(b, arg);
    }

    private void addEntries(StringBuilder b) {
        if (assemblyUser != null) {
            String tmpDir = createTempDir();
            copyAddEntries(b,tmpDir);

            String[] userParts = StringUtils.split(assemblyUser, ":");
            String userArg = userParts.length > 1 ? userParts[0] + ":" + userParts[1] : userParts[0];
            String chmod = "chown -R " + userArg + " " + tmpDir + " && cp -rp " + tmpDir + "/* / && rm -rf " + tmpDir;
            if (userParts.length > 2) {
                DockerFileKeyword.USER.addTo(b, "root");
                DockerFileKeyword.RUN.addTo(b, chmod);
                DockerFileKeyword.USER.addTo(b, userParts[2]);
            } else {
                DockerFileKeyword.RUN.addTo(b, chmod);
            }
        } else {
            copyAddEntries(b, "");
        }
    }

    private String createTempDir() {
         return "/tmp/" + UUID.randomUUID().toString();
    }

    private void copyAddEntries(StringBuilder b, String topLevelDir) {
        for (AddEntry entry : addEntries) {
            String dest = topLevelDir + (basedir.equals("/") ? "" : basedir) + "/" + entry.destination;
            DockerFileKeyword.COPY.addTo(b, entry.source, dest);
        }
    }

    private void addEnv(StringBuilder b) {
        addMap(b, DockerFileKeyword.ENV, envEntries);
    }

    private void addLabels(StringBuilder b) {
        addMap(b, DockerFileKeyword.LABEL, labels);
    }

    private void addMap(StringBuilder b,DockerFileKeyword keyword, Map<String,String> map) {
        if (map != null && map.size() > 0) {
            String entries[] = new String[map.size()];
            int i = 0;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                entries[i++] = quote(entry.getKey()) + "=" + quote(entry.getValue());
            }
            keyword.addTo(b, entries);
        }
    }

    private String quote(String value) {
        return StringUtils.quoteAndEscape(value,'"');
    }

    private void addPorts(StringBuilder b) {
        if (ports.size() > 0) {
            String[] portsS = new String[ports.size()];
            int i = 0;
            for (Integer port : ports) {
                portsS[i++] = port.toString();
            }
            DockerFileKeyword.EXPOSE.addTo(b, portsS);
        }
    }

    private void addOptimisation() {
        if (runCmds != null && !runCmds.isEmpty() && shouldOptimise) {
            String optimisedRunCmd = StringUtils.join(runCmds.iterator(), " && ");
            runCmds.clear();
            runCmds.add(optimisedRunCmd);
        }
    }

	private void addRun(StringBuilder b) {
		for (String run : runCmds) {
            DockerFileKeyword.RUN.addTo(b, run);
		}
	}

    private void addVolumes(StringBuilder b) {
        if (exportBasedir != null ? exportBasedir : baseImage == null) {
            addVolume(b, basedir);
        }

        for (String volume : volumes) {
            addVolume(b, volume);
        }
    }

    private void addVolume(StringBuilder buffer, String volume) {
        while (volume.endsWith("/")) {
            volume = volume.substring(0, volume.length() - 1);
        }
        // don't export '/'
        if (volume.length() > 0) {
            DockerFileKeyword.VOLUME.addTo(buffer, "[\"" + volume + "\"]");
        }
    }

    // ==========================================================================
    // Builder stuff ....
    public DockerFileBuilder() {}

    public DockerFileBuilder baseImage(String baseImage) {
        if (baseImage != null) {
            this.baseImage = baseImage;
        }
        return this;
    }

    public DockerFileBuilder maintainer(String maintainer) {
        this.maintainer = maintainer;
        return this;
    }

    public DockerFileBuilder workdir(String workdir) {
        this.workdir = workdir;
        return this;
    }

    public DockerFileBuilder basedir(String dir) {
        if (dir != null) {
            if (!dir.startsWith("/")) {
                throw new IllegalArgumentException("'basedir' must be an absolute path starting with / (and not " +
                                                   "'" + basedir + "')");
            }
            basedir = dir;
        }
        return this;
    }

    public DockerFileBuilder cmd(Arguments cmd) {
        this.cmd = cmd;
        return this;
    }

    public DockerFileBuilder entryPoint(Arguments entryPoint) {
        this.entryPoint = entryPoint;
        return this;
    }

    public DockerFileBuilder assemblyUser(String assemblyUser) {
        this.assemblyUser = assemblyUser;
        return this;
    }

    public DockerFileBuilder user(String user) {
        this.user = user;
        return this;
    }

    public DockerFileBuilder add(String source, String destination) {
        this.addEntries.add(new AddEntry(source, destination));
        return this;
    }

    public DockerFileBuilder expose(List<String> ports) {
        if (ports != null) {
            for (String port : ports) {
                if (port != null) {
                    try {
                        this.ports.add(Integer.parseInt(port));
                    } catch (NumberFormatException exp) {
                        throw new IllegalArgumentException("Non numeric port " + port + " specified in port mapping",exp);
                    }
                }
            }
        }
        return this;
    }

    /**
     * Adds the RUN Commands within the build image section
     * @param runCmds
     * @return
     */
    public DockerFileBuilder run(List<String> runCmds) {
        if (runCmds != null) {
            for (String cmd : runCmds) {
                if (!StringUtils.isEmpty(cmd)) {
                    this.runCmds.add(cmd);
                }
            }
        }
        return this;
    }

    public DockerFileBuilder exportBasedir(Boolean exportBasedir) {
        this.exportBasedir = exportBasedir;
        return this;
    }

    public DockerFileBuilder env(Map<String, String> values) {
        if (values != null) {
            this.envEntries.putAll(values);
            validateMap(envEntries);
        }
        return this;
    }

    public DockerFileBuilder labels(Map<String,String> values) {
        if (values != null) {
            this.labels.putAll(values);
            validateMap(labels);
        }
        return this;
    }

    public DockerFileBuilder volumes(List<String> volumes) {
        if (volumes != null) {
           this.volumes.addAll(volumes);
        }
        return this;
    }

    public DockerFileBuilder optimise() {
        this.shouldOptimise = true;
        return this;
    }

    private void validateMap(Map<String, String> env) {
        for (Map.Entry<String,String> entry : env.entrySet()) {
            if (entry.getValue() == null || entry.getValue().length() == 0) {
                throw new IllegalArgumentException("Environment variable '" +
                                                   entry.getKey() + "' must not be null or empty if building an image");
            }
        }
    }

    // All entries required, destination is relative to exportDir
    private static final class AddEntry {
        private String source,destination;

        private AddEntry(String src, String dest) {
            source = src;

            // Strip leading slashes
            destination = dest;

            // squeeze slashes
            while (destination.startsWith("/")) {
                destination = destination.substring(1);
            }
        }
    }

}
