/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.benchto.driver.listeners.profiler.async;

import io.trino.benchto.driver.listeners.profiler.QueryProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "benchmark.feature.profiler.async", name = "enabled", havingValue = "true")
public class AsyncProfiler
        implements QueryProfiler
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncProfiler.class);

    @Autowired
    AsyncProfilerProperties profilerProperties;
    private static final String[] commandSignature = new String[] {"[Ljava.lang.String;"};

    @Override
    @Retryable(value = IOException.class, backoff = @Backoff(200), maxAttempts = 2)
    public void start(String workerName, String benchmarkName, String queryName, int sequenceId)
    {
        Path asyncLibraryPath = Path.of(profilerProperties.getAsyncLibraryPath());
        String url = String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", workerName, profilerProperties.getJmx().getPort());
        try (JMXConnector jmxConnector = JMXConnectorFactory.connect(new JMXServiceURL(url), null)) {
            MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();

            String outputFile = Path.of(profilerProperties.getOutputPath().toString(), benchmarkName, "%s_%d.jfr".formatted(queryName, sequenceId)).toString();
            String events = profilerProperties.getEvents().stream().map(Enum::toString).collect(Collectors.joining(","));
            String command = "\"start,event=%s,file=%s,jfr\"".formatted(events, outputFile);
            Object[] args = new Object[] {
                    new String[] {
                            asyncLibraryPath.toString(),
                            command
                    }
            };
            LOG.info("Asyncprofiler start command is %s for query=%s, sequenceId=%d, result=%s at side %s".formatted(command, queryName, sequenceId, outputFile, workerName));
            Object result = mBeanServerConnection.invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "jvmtiAgentLoad",
                    args,
                    commandSignature);
            LOG.info("Result of starting is: '%s' at %s side".formatted(result, workerName));
        }
        catch (Exception e) {
            LOG.error("Starting asyncprofiler for worker '%s' failed".formatted(workerName), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    @Retryable(value = IOException.class, backoff = @Backoff(200), maxAttempts = 2)
    public void stop(String workerName, String benchmarkName, String queryName, int sequenceId)
    {
        String url = String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", workerName, profilerProperties.getJmx().getPort());

        try (JMXConnector jmxConnector = JMXConnectorFactory.connect(new JMXServiceURL(url), null)) {
            MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
            String command = "\"stop\"";
            Object[] args = new Object[] {
                    new String[] {
                            profilerProperties.getAsyncLibraryPath(),
                            command
                    }
            };
            Object result = mBeanServerConnection.invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "jvmtiAgentLoad",
                    args,
                    commandSignature);
            LOG.info("Result of stopping is: '%s' at %s side".formatted(result, workerName));
        }
        catch (Exception e) {
            LOG.error("Stopping asyncprofiler for worker %s failed at %s side".formatted(workerName, workerName), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString()
    {
        return "asyncprofiler";
    }
}
