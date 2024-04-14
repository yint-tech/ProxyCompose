package cn.iinti.proxycompose.auth;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

public class AuthRules {
    /**
     * IP匹配前缀树
     */
    private final IpTrie ipTrie = new IpTrie();


    @Getter
    @Setter
    private String authAccount;

    @Getter
    @Setter
    private String authPassword;

    public void addCidrIpConfig(String ipConfig) {
        ipTrie.insert(ipConfig);
    }


    public boolean doAuth(String ip) {
        return ipTrie.has(ip);
    }

    public boolean doAuth(String authUserName, String authPassword) {
        return StringUtils.equals(this.authAccount, authUserName)
                && StringUtils.equals(this.authPassword, authPassword);
    }
}
