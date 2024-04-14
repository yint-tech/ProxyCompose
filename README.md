# ProxyCompose
这是malenia的开源分支，主要用于给商业代码引流😊

ProxyCompose是一个对代理IP池进行二次组合的工具，用户可以将多个采购的IP资源进行二次组合，使用统一的账户密码，服务器端口等信息进行统一访问。

## 特性

1. 访问统一：如论是什么IP资源供应商，业务代码均接入ProxyCompose，IP资源供应商的变动不会影响业务代码
2. 池间路由：对于多个IP资源供应商，支持浮动流量比例动态调控，即根据IP池健康评估，弹性伸缩各个IP池的流量。某个IP池挂了不影响整体业务
3. 协议转换：你可以使用ProxyCompose实现http/https/socks5几种代理协议的转换，这样即使采购的代理资源仅支持socks5，也能转换为https代理
4. 池化加速：ProxyCompose内置了一个高效的IP池模块，可以对IP资源的访问进行探测、评分、连接池等工作，提高IP资源使用成功率


## 最简配置
```ini
[global]
# 鉴权用户，即用户连接到proxy_compose的鉴权
auth_username=iinti
# 鉴权密码
auth_password=iinti

# 定义IP资源，即从IP供应商采购的IP资源,要求至少配置一个IP资源
[source:dailiyun]
# IP资源下载连接
loadURL=http://修改这里.user.xiecaiyun.com/api/proxies?action=getText&key=修改这里&count=修改这里&word=&rand=false&norepeat=false&detail=false&ltime=0
# Ip供应提供的代理账户（如果是白名单，后者无鉴权，则无需配置）
upstreamAuthUser=修改这里
# Ip供应提供的代密码
upstreamAuthPassword=修改这里
# IP池大小，重要
poolSize=10
```
## 特别说明
ComposeProxy本身能做的工作非常丰富，更多想象空间可以参考我们对应的商业分支：[malenia](https://malenia.iinti.cn/malenia-doc/)
用户如提交任何新的功能（即使和商业分支重叠）均可以被接收