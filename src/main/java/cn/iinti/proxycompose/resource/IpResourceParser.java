package cn.iinti.proxycompose.resource;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;

public interface IpResourceParser {
    List<ProxyIp> parse(String responseText);

    public static IpResourceParser resolve(String format) {
        return "json".equalsIgnoreCase(format) ?
                JSONParser.instance : SmartParser.instance;
    }

    class JSONParser implements IpResourceParser {

        public static JSONParser instance = new JSONParser();

        @Override
        public List<ProxyIp> parse(String responseText) {
            responseText = StringUtils.trimToEmpty(responseText);
            if (responseText.startsWith("[")) {
                return JSONArray.parseArray(responseText, ProxyIp.class);
            }
            if (responseText.startsWith("{")) {
                return Lists.newArrayList(JSONObject.parseObject(responseText, ProxyIp.class));
            }
            return Collections.emptyList();
        }
    }
}
