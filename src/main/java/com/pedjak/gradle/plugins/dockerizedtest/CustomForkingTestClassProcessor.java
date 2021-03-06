package com.pedjak.gradle.plugins.dockerizedtest;

import java.io.File;
import java.net.URL;
import java.util.List;

import org.gradle.api.Action;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.TestClassRunInfo;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import org.gradle.api.internal.tasks.testing.worker.RemoteTestClassProcessor;
import org.gradle.api.internal.tasks.testing.worker.TestEventSerializer;
import org.gradle.internal.remote.ObjectConnection;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.internal.worker.WorkerProcess;
import org.gradle.process.internal.worker.WorkerProcessBuilder;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.util.CollectionUtils;

public class CustomForkingTestClassProcessor implements TestClassProcessor
{
    private final WorkerProcessFactory workerFactory;
    private final WorkerTestClassProcessorFactory processorFactory;
    private final JavaForkOptions options;
    private final Iterable<File> classPath;
    private final Action<WorkerProcessBuilder> buildConfigAction;
    private final ModuleRegistry moduleRegistry;
    private RemoteTestClassProcessor remoteProcessor;
    private WorkerProcess workerProcess;
    private TestResultProcessor resultProcessor;

    public CustomForkingTestClassProcessor(WorkerProcessFactory workerFactory, WorkerTestClassProcessorFactory processorFactory, JavaForkOptions options, Iterable<File> classPath, Action<WorkerProcessBuilder> buildConfigAction, ModuleRegistry moduleRegistry) {
        this.workerFactory = workerFactory;
        this.processorFactory = processorFactory;
        this.options = options;
        this.classPath = classPath;
        this.buildConfigAction = buildConfigAction;
        this.moduleRegistry = moduleRegistry;
    }

    @Override
    public void startProcessing(TestResultProcessor resultProcessor) {
        this.resultProcessor = resultProcessor;
    }

    @Override
    public void processTestClass(TestClassRunInfo testClass) {
        int i = 0;
        RuntimeException exception = null;
        while (remoteProcessor == null && i < 10)
        {
            try
            {
                remoteProcessor = forkProcess();
                exception = null;
                break;
            }
            catch (RuntimeException e)
            {
                exception = e;
                i++;
            }
        }

        if (exception != null) throw exception;
        remoteProcessor.processTestClass(testClass);
    }

    RemoteTestClassProcessor forkProcess() {
        WorkerProcessBuilder builder = workerFactory.create(new ForciblyStoppableTestWorker(processorFactory));
        builder.setBaseName("Gradle Test Executor");
        builder.setImplementationClasspath(getTestWorkerImplementationClasspath());
        builder.applicationClasspath(classPath);
        options.copyTo(builder.getJavaCommand());
        buildConfigAction.execute(builder);

        workerProcess = builder.build();
        workerProcess.start();

        ObjectConnection connection = workerProcess.getConnection();
        connection.useParameterSerializers(TestEventSerializer.create());
        connection.addIncoming(TestResultProcessor.class, resultProcessor);
        RemoteTestClassProcessor remoteProcessor = connection.addOutgoing(RemoteTestClassProcessor.class);
        connection.connect();
        remoteProcessor.startProcessing();
        return remoteProcessor;
    }

    List<URL> getTestWorkerImplementationClasspath() {
        return CollectionUtils.flattenCollections(URL.class,
                moduleRegistry.getModule("gradle-core").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-logging").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-messaging").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-base-services").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-cli").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-native").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-testing-base").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-testing-jvm").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getModule("gradle-process-services").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getExternalModule("guava-jdk5").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getExternalModule("slf4j-api").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getExternalModule("jul-to-slf4j").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getExternalModule("native-platform").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getExternalModule("kryo").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getExternalModule("commons-lang").getImplementationClasspath().getAsURLs(),
                moduleRegistry.getExternalModule("junit").getImplementationClasspath().getAsURLs(),
                CustomForkingTestClassProcessor.class.getProtectionDomain().getCodeSource().getLocation()
        );
    }

    @Override
    public void stop() {
        if (remoteProcessor != null) {
            try {
                remoteProcessor.stop();
                workerProcess.waitForStop();
            } finally {
                // do nothing
            }
        }
    }
}
