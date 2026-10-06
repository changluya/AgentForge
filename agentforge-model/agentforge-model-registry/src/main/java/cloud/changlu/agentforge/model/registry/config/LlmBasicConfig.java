package cloud.changlu.agentforge.model.registry.config;

import java.util.Properties;

/**
 * @description llm基础配置类
 * @author changlu
 * @date 2026/9/16
 */
public class LlmBasicConfig {

    // 大模型厂商Code 请参考LlmEnum中的code来进行填写
    private Integer provider;

    // 模型的连接地址
    private String url;
    // 模型名称
    private String modelName;
    // 密钥
    private String apiKey;

    // 大模型相关配置参数
    private Properties props;

    public LlmBasicConfig() {}

    private LlmBasicConfig(Builder builder) {
        this.provider = builder.provider;
        this.url = builder.url;
        this.modelName = builder.modelName;
        this.apiKey = builder.apiKey;
        this.props = builder.props;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Integer getProvider() {
        return provider;
    }

    public void setProvider(Integer provider) {
        this.provider = provider;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Properties getProps() {
        return props;
    }

    public void setProps(Properties props) {
        this.props = props;
    }

    public static class Builder {
        private Integer provider;
        private String url;
        private String modelName;
        private String apiKey;
        private Properties props;

        public Builder provider(Integer provider) {
            this.provider = provider;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder props(Properties props) {
            this.props = props;
            return this;
        }

        public Builder prop(String key, String value) {
            if (this.props == null) {
                this.props = new Properties();
            }
            this.props.setProperty(key, value);
            return this;
        }

        public LlmBasicConfig build() {
            return new LlmBasicConfig(this);
        }
    }
}
