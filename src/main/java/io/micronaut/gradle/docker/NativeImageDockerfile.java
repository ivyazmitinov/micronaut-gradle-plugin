package io.micronaut.gradle.docker;

import com.bmuschko.gradle.docker.tasks.image.Dockerfile;
import io.micronaut.gradle.graalvm.NativeImageTask;
import org.gradle.api.Action;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.StopActionException;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.internal.jvm.Jvm;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Specialization of {@link Dockerfile} for building native images.
 *
 * @author gkrocher
 * @since 1.0.0
 */
public abstract class NativeImageDockerfile extends Dockerfile implements DockerBuildOptions {

    @Input
    private final Property<String> jdkVersion;
    @Input
    private final Property<String> graalVersion;
    @Input
    private final Property<String> graalImage;
    @Input
    @Nullable
    private final Property<String> baseImage;
    @Input
    private final ListProperty<String> args;
    @Input
    private final ListProperty<Integer> exposedPorts;
    @Input
    private final Property<Boolean> requireGraalSdk;
    @Input
    private final Property<DockerBuildStrategy> buildStrategy;
    @Input
    private final Property<String> defaultCommand;


    public NativeImageDockerfile() {
        Project project = getProject();
        setGroup(BasePlugin.BUILD_GROUP);
        setDescription("Builds a Docker File for Native Image");
        getDestFile().set(project.getLayout().getBuildDirectory().file("docker/DockerfileNative"));
        ObjectFactory objects = project.getObjects();
        this.buildStrategy = objects.property(DockerBuildStrategy.class)
                .convention(DockerBuildStrategy.DEFAULT);
        this.jdkVersion = objects.property(String.class);
        this.requireGraalSdk = objects.property(Boolean.class).convention(true);
        JavaVersion javaVersion = Jvm.current().getJavaVersion();
        if (javaVersion == null) {
            jdkVersion.convention("java11");
        } else if (javaVersion.isCompatibleWith(JavaVersion.toVersion(16))) {
            jdkVersion.convention("java16");
        } else if (javaVersion.isJava11Compatible()) {
            jdkVersion.convention("java11");
        } else {
            jdkVersion.convention("java8");
        }
        this.graalVersion = objects.property(String.class)
                .convention("21.2.0");
        this.graalImage = objects.property(String.class)
                .convention(graalVersion.map(version -> "ghcr.io/graalvm/graalvm-ce:" + jdkVersion.get() + '-' + version));
        this.baseImage = objects.property(String.class)
                .convention("null");
        this.args = objects.listProperty(String.class);
        this.exposedPorts = objects.listProperty(Integer.class);
        this.defaultCommand = objects.property(String.class).convention("none");

        //noinspection Convert2Lambda
        doLast(new Action<Task>() {
            @Override
            public void execute(Task task) {
                java.io.File f = NativeImageDockerfile.this.getDestFile().get().getAsFile();
                System.out.println("Dockerfile written to: " + f.getAbsolutePath());
            }
        });
    }

    @TaskAction
    @Override
    public void create() {
        super.create();
    }

    /**
     * @return Whether a Graal SDK is required (defaults to 'true').
     */
    public Property<Boolean> getRequireGraalSdk() {
        return requireGraalSdk;
    }

    /**
     * @return The JDK version to use with native image.
     */
    public Property<String> getJdkVersion() {
        return jdkVersion;
    }

    /**
     * @return The build startegy
     */
    public Property<DockerBuildStrategy> getBuildStrategy() {
        return buildStrategy;
    }

    /**
     * @return The arguments to pass to the native image executable when starting up in the docker container.
     */
    @Override
    public ListProperty<String> getArgs() {
        return args;
    }

    /**
     * @return the GraalVM docker image to use
     */
    public Property<String> getGraalImage() {
        return graalImage;
    }

    /**
     * @return The Graal version to use.
     */
    public Property<String> getGraalVersion() {
        return graalVersion;
    }

    /**
     * @return The base image to use
     */
    @Override
    @Nullable
    public Property<String> getBaseImage() {
        return baseImage;
    }

    @Override
    public Property<String> getDefaultCommand() {
        return this.defaultCommand;
    }

    @Override
    public ListProperty<Integer> getExposedPorts() {
        return this.exposedPorts;
    }

    private void setupInstructions(List<Instruction> additionalInstructions) {
        DockerBuildStrategy buildStrategy = this.buildStrategy.getOrElse(DockerBuildStrategy.DEFAULT);
        JavaApplication javaApplication = getProject().getExtensions().getByType(JavaApplication.class);
        final TaskContainer tasks = getProject().getTasks();
        Task nit = tasks.findByName("nativeImage");
        NativeImageTask nativeImageTask = (NativeImageTask) tasks.getByName("internalDockerNativeImageTask");
        Set<java.io.File> configDirs = Collections.emptySet();
        if (nit instanceof NativeImageTask) {
            final NativeImageTask sourceTask = (NativeImageTask) nit;
            configDirs = sourceTask.getConfigDirectories().getFiles();
            List<String> args = sourceTask.getArgs();
            nativeImageTask.args(args);
            nativeImageTask.getJvmArgs().set(sourceTask.getJvmArgs());
            nativeImageTask.getSystemProperties().set(sourceTask.getSystemProperties());
            nativeImageTask.getMain().convention(sourceTask.getMain());
        } else {
            throw new StopActionException("No native image task present! Must be used in conjunction with a NativeImageTask.");
        }
        if (buildStrategy == DockerBuildStrategy.LAMBDA) {
            from(new From("amazonlinux:latest").withStage("graalvm"));
            environmentVariable("LANG", "en_US.UTF-8");
            runCommand("yum install -y gcc gcc-c++ libc6-dev zlib1g-dev curl bash zlib zlib-devel zlib-static zip tar gzip");
            String jdkVersion = this.jdkVersion.get();
            String graalVersion = this.graalVersion.get();
            String fileName = "graalvm-ce-" + jdkVersion + "-linux-amd64-" + graalVersion + ".tar.gz";
            runCommand("curl -4 -L https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-" + graalVersion + "/" + fileName + " -o /tmp/" + fileName);
            runCommand("tar -zxf /tmp/" + fileName + " -C /tmp && mv /tmp/graalvm-ce-" + jdkVersion + "-" + graalVersion + " /usr/lib/graalvm");
            runCommand("rm -rf /tmp/*");
            runCommand("/usr/lib/graalvm/bin/gu install native-image");
            defaultCommand("/usr/lib/graalvm/bin/native-image");
            environmentVariable("PATH", "/usr/lib/graalvm/bin:${PATH}");
            from(new From("graalvm").withStage("builder"));
        } else {
            from(new From(graalImage.get()).withStage("graalvm"));
            runCommand("gu install native-image");
        }
        MicronautDockerfile.setupResources(this);
        // use native-image from docker image
        nativeImageTask.setExecutable("native-image");
        // use hard coded image name
        nativeImageTask.setImageName("application");
        if (buildStrategy == DockerBuildStrategy.ORACLE_FUNCTION) {
            javaApplication.getMainClass().set("com.fnproject.fn.runtime.EntryPoint");
            nativeImageTask.setMain("com.fnproject.fn.runtime.EntryPoint");
            nativeImageTask.args("--report-unsupported-elements-at-runtime");
        } else if (buildStrategy == DockerBuildStrategy.LAMBDA) {
            if (!javaApplication.getMainClass().isPresent()) {
                javaApplication.getMainClass().set("io.micronaut.function.aws.runtime.MicronautLambdaRuntime");
            }
            if (!nativeImageTask.getMain().isPresent()) {
                nativeImageTask.setMain("io.micronaut.function.aws.runtime.MicronautLambdaRuntime");
            }
        }
        nativeImageTask.configure(false);
        // clear out classpath
        nativeImageTask.setClasspath(getProject().files());
        Set<java.io.File> finalConfigDirs = configDirs;
        List<String> commandLine = nativeImageTask.getCommandLine()
                .stream()
                .map(line -> {
                    for (java.io.File configDir : finalConfigDirs) {
                        String srcDir = configDir.getAbsolutePath();
                        if (line.contains(srcDir)) {
                            // This is a quick and dirty fix for #246 which is going to be removed
                            // once we use the official GraalVM plugin
                            return line.replace(srcDir, "/home/app/resources");
                        }
                    }
                    return line;
                })
                .collect(Collectors.toList());
        commandLine.add("-cp");
        commandLine.add("/home/app/libs/*.jar:/home/app/resources:/home/app/application.jar");

        String baseImage = resolveBaseImageForBuildStrategy(buildStrategy);

        // add --static if image is scratch
        if (baseImage.equalsIgnoreCase("scratch") && !commandLine.contains("--static")) {
            commandLine.add("--static");
        }

        // to build a "mostly" static native-image if image when using distroless
        if (baseImage.contains("distroless") && !commandLine.contains("-H:+StaticExecutableWithDynamicLibC")) {
            commandLine.add("-H:+StaticExecutableWithDynamicLibC");
        }

        String nativeImageCommand = String.join(" ", commandLine);
        runCommand(nativeImageCommand);
        switch (buildStrategy) {
            case ORACLE_FUNCTION:
                from(new From("fnproject/fn-java-fdk:" + getProjectFnVersion()).withStage("fnfdk"));
                from(baseImage);
                workingDir("/function");
                copyFile(new CopyFile("/home/app/application", "/function/func").withStage("graalvm"));
                copyFile(new CopyFile("/function/runtime/lib/*", ".").withStage("fnfdk"));
                entryPoint(args.map(strings -> {
                    List<String> newList = new ArrayList<>(strings.size() + 1);
                    newList.add("./func");
                    newList.addAll(strings);
                    newList.add("-Djava.library.path=/function");
                    return newList;
                }));
                String cmd = this.defaultCommand.get();
                if ("none".equals(cmd)) {
                    super.defaultCommand("io.micronaut.oraclecloud.function.http.HttpFunction::handleRequest");
                } else {
                    super.defaultCommand(cmd);
                }
                break;
            case LAMBDA:
                from(baseImage);
                workingDir("/function");
                runCommand("yum install -y zip");
                copyFile(new CopyFile("/home/app/application", "/function/func").withStage("builder"));
                String funcCmd = String.join(" ", args.map(strings -> {
                    List<String> newList = new ArrayList<>(strings.size() + 1);
                    newList.add("./func");
                    newList.addAll(strings);
                    newList.add("-Djava.library.path=$(pwd)");
                    return newList;
                }).get());
                runCommand("echo \"#!/bin/sh\" >> bootstrap && echo \"set -euo pipefail\" >> bootstrap && echo \"" + funcCmd + "\" >> bootstrap");
                runCommand("chmod 777 bootstrap");
                runCommand("chmod 777 func");
                runCommand("zip -j function.zip bootstrap func");
                getInstructions().addAll(additionalInstructions);
                entryPoint("/function/func");
                break;
            default:
                from(baseImage);
                // mandatory dependency for alpine-glibc docker images
                if (baseImage.contains("alpine-glibc")) {
                    runCommand("apk update && apk add libstdc++");
                }
                exposePort(this.exposedPorts);
                getInstructions().addAll(additionalInstructions);
                copyFile(new CopyFile("/home/app/application", "/app/application").withStage("graalvm"));
                entryPoint(args.map(strings -> {
                    List<String> newList = new ArrayList<>(strings.size() + 1);
                    newList.add("/app/application");
                    newList.addAll(strings);
                    return newList;
                }));
                break;
        }
    }

    /**
     * The Dockerfile task requires a 'from' at least, but this
     * will be replaced in setupTaskPostEvaluate where we also
     * incorporate commands supplied by the build.gradle file (if required)
     */
    void setupDockerfileInstructions() {
        from("placeholder");
    }

    /**
     * This is executed post project evaluation
     */
    void setupNativeImageTaskPostEvaluate() {
        // Get any custom instructions the user may or may not have entered, but ignoring our 'from' placeholder
        List<Instruction> additionalInstructions = new ArrayList<>(getInstructions().get().subList(1, getInstructions().get().size()));
        // Reset the instructions to empty
        getInstructions().set(new ArrayList<>());
        setupInstructions(additionalInstructions);
    }

    /**
     * Adds additional args to pass to the native image executable.
     *
     * @param args The args
     * @return This instance.
     */
    @Override
    public NativeImageDockerfile args(String... args) {
        this.args.addAll(args);
        return this;
    }

    @Override
    public NativeImageDockerfile baseImage(String imageName) {
        if (imageName != null) {
            this.baseImage.set(imageName);
        }
        return this;
    }

    @Override
    public DockerBuildOptions exportPorts(Integer... ports) {
        this.exposedPorts.set(Arrays.asList(ports));
        return this;
    }

    public NativeImageDockerfile graalImage(String imageName) {
        if (imageName != null) {
            this.graalImage.set(imageName);
        }
        return this;
    }

    private String getProjectFnVersion() {
        JavaVersion javaVersion = Jvm.current().getJavaVersion();
        if (javaVersion != null && javaVersion.isJava11Compatible()) {
            return "jre11-latest";
        }
        return "latest";
    }

    private String resolveBaseImageForBuildStrategy(DockerBuildStrategy buildStrategy) {
        String baseImage = this.baseImage.get();
        // why I have to do this horrible hack on the Gradle gods know
        if ("null".equalsIgnoreCase(baseImage)) {
            baseImage = null;
        }

        if (buildStrategy == DockerBuildStrategy.LAMBDA && baseImage == null) {
            baseImage = "amazonlinux:latest";
        } else if (baseImage == null) {
            baseImage = "frolvlad/alpine-glibc:alpine-3.12";
        }

        return baseImage;
    }
}
