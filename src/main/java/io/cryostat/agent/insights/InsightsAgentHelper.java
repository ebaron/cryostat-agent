/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent.insights;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import io.cryostat.agent.ConfigModule;
import io.cryostat.agent.model.PluginInfo;

import com.redhat.insights.InsightsReport;
import com.redhat.insights.InsightsReportController;
import com.redhat.insights.agent.AgentBasicReport;
import com.redhat.insights.agent.AgentConfiguration;
import com.redhat.insights.agent.ClassNoticer;
import com.redhat.insights.agent.InsightsAgentHttpClient;
import com.redhat.insights.http.InsightsHttpClient;
import com.redhat.insights.jars.JarInfo;
import com.redhat.insights.logging.InsightsLogger;
import com.redhat.insights.tls.PEMSupport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InsightsAgentHelper {

    private static final String INSIGHTS_SVC = "INSIGHTS_SVC";

    private static final InsightsLogger log =
            new SLF4JWrapper(LoggerFactory.getLogger(InsightsAgentHelper.class));
    private static final BlockingQueue<JarInfo> jarsToSend = new LinkedBlockingQueue<>();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    private final Instrumentation instrumentation;

    private final Config config;

    public InsightsAgentHelper(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.config = ConfigProvider.getConfig();
    }

    public boolean isInsightsEnabled(PluginInfo pluginInfo) {
        return pluginInfo.getEnv().containsKey(INSIGHTS_SVC);
    }

    public void runInsightsAgent(PluginInfo pluginInfo) {
        log.info("Starting Red Hat Insights client");
        String server = pluginInfo.getEnv().get(INSIGHTS_SVC);
        Objects.requireNonNull(server, "Insights server is missing");
        String appName = config.getValue(ConfigModule.CRYOSTAT_AGENT_APP_NAME, String.class);

        // Add Insights instrumentation
        instrument(instrumentation);

        Map<String, String> out = new HashMap<>();
        out.put("name", appName);
        out.put("base_url", server);
        // Will be replaced by the Insights Proxy
        out.put("token", "dummy");
        AgentConfiguration config = new AgentConfiguration(out);

        final InsightsReport simpleReport = AgentBasicReport.of(log, config);
        final PEMSupport pem = new PEMSupport(log, config);

        final Supplier<InsightsHttpClient> httpClientSupplier =
                () -> new InsightsAgentHttpClient(log, config, () -> pem.createTLSContext());
        final InsightsReportController controller =
                InsightsReportController.of(
                        log, config, simpleReport, httpClientSupplier, jarsToSend);
        controller.generate();
    }

    private static void instrument(Instrumentation instrumentation) {
        ClassNoticer noticer = new ClassNoticer(log, jarsToSend);
        instrumentation.addTransformer(noticer);
    }

    private static class SLF4JWrapper implements InsightsLogger {

        private final Logger delegate;

        SLF4JWrapper(Logger delegate) {
            this.delegate = delegate;
        }

        @Override
        public void debug(String message) {
            delegate.debug(message);
        }

        @Override
        public void debug(String message, Throwable err) {
            delegate.debug(message, err);
        }

        @Override
        public void error(String message) {
            delegate.error(message);
        }

        @Override
        public void error(String message, Throwable err) {
            delegate.error(message, err);
        }

        @Override
        public void info(String message) {
            delegate.info(message);
        }

        @Override
        public void warning(String message) {
            delegate.warn(message);
        }

        @Override
        public void warning(String message, Throwable err) {
            delegate.warn(message, err);
        }
    }
}
