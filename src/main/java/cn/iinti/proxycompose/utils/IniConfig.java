package cn.iinti.proxycompose.utils;

import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.util.TypeUtils;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.ini4j.ConfigParser;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Consumer;

public abstract class IniConfig {
    private final ConfigParser config;
    protected final String section;

    public IniConfig(ConfigParser config, String section) {
        this.config = config;
        this.section = section;
    }

    @SneakyThrows
    protected void acceptConfig(String key, Consumer<String> consumer) {
        if (config.hasOption(section, key)) {
            consumer.accept(config.get(section, key));
        }
    }

    public abstract class ConfigValue<V> {
        public final V value;
        public final String key;

        public ConfigValue(String key, V defaultValue) {
            this.key = key;
            this.value = calcValue(key, defaultValue);
        }

        @SneakyThrows
        private V calcValue(String key, V defaultValue) {
            key = key.toLowerCase();
            Class<V> superClassGenericType = getSuperClassGenericType(getClass());
            if (config.hasOption(section, key)) {
                String config = IniConfig.this.config.get(section, key);
                if (StringUtils.isNotBlank(config)) {
                    return TypeUtils.cast(config, superClassGenericType, ParserConfig.getGlobalInstance());
                }
            }
            return defaultValue;
        }

        @SuppressWarnings("unchecked")
        private <T> Class<T> getSuperClassGenericType(Class<?> clazz) {
            Type genType = clazz.getGenericSuperclass();
            if (!(genType instanceof ParameterizedType)) {
                return (Class<T>) Object.class;
            }
            Type[] params = ((ParameterizedType) genType).getActualTypeArguments();
            if (0 == params.length) {
                return (Class<T>) Object.class;
            } else if (!(params[0] instanceof Class)) {
                return (Class<T>) Object.class;
            } else {
                return (Class<T>) params[0];
            }
        }
    }


    public class StringConfigValue extends ConfigValue<String> {
        public StringConfigValue(String configKey, String defaultValue) {
            super(configKey, defaultValue);
        }
    }

    public class BooleanConfigValue extends ConfigValue<Boolean> {
        public BooleanConfigValue(String configKey, Boolean defaultValue) {
            super(configKey, defaultValue);
        }
    }

    public class IntegerConfigValue extends ConfigValue<Integer> {
        public IntegerConfigValue(String configKey, Integer defaultValue) {
            super(configKey, defaultValue);
        }
    }
}
