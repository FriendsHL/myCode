package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "skillforge")
public class WebToolsProperties {

    private Websearch websearch = new Websearch();
    private Webfetch webfetch = new Webfetch();

    public Websearch getWebsearch() {
        return websearch;
    }

    public void setWebsearch(Websearch websearch) {
        this.websearch = websearch != null ? websearch : new Websearch();
    }

    public Webfetch getWebfetch() {
        return webfetch;
    }

    public void setWebfetch(Webfetch webfetch) {
        this.webfetch = webfetch != null ? webfetch : new Webfetch();
    }

    public static class Websearch {
        private List<String> backendPriority = new ArrayList<>(List.of("tavily", "exa", "duckduckgo"));
        private Provider tavily = new Provider();
        private Provider exa = new Provider();

        public List<String> getBackendPriority() {
            return backendPriority;
        }

        public void setBackendPriority(List<String> backendPriority) {
            this.backendPriority = backendPriority == null || backendPriority.isEmpty()
                    ? new ArrayList<>(List.of("tavily", "exa", "duckduckgo"))
                    : new ArrayList<>(backendPriority);
        }

        public Provider getTavily() {
            return tavily;
        }

        public void setTavily(Provider tavily) {
            this.tavily = tavily != null ? tavily : new Provider();
        }

        public Provider getExa() {
            return exa;
        }

        public void setExa(Provider exa) {
            this.exa = exa != null ? exa : new Provider();
        }
    }

    public static class Provider {
        private String apiKey;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class Webfetch {
        private Robots robots = new Robots();

        public Robots getRobots() {
            return robots;
        }

        public void setRobots(Robots robots) {
            this.robots = robots != null ? robots : new Robots();
        }
    }

    public static class Robots {
        private List<String> hostAllowlist = new ArrayList<>(List.of("localhost", "127.0.0.1"));

        public List<String> getHostAllowlist() {
            return hostAllowlist;
        }

        public void setHostAllowlist(List<String> hostAllowlist) {
            this.hostAllowlist = hostAllowlist == null || hostAllowlist.isEmpty()
                    ? new ArrayList<>(List.of("localhost", "127.0.0.1"))
                    : new ArrayList<>(hostAllowlist);
        }
    }
}
