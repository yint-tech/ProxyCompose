# ProxyCompose

这是malenia的开源分支，主要用于给商业代码引流😊

ProxyCompose是一个对代理IP池进行二次组合的工具，用户可以将多个采购的IP资源进行二次组合，使用统一的账户密码，服务器端口等信息进行统一访问。

交流群： 加微信：（iinti_cn）拉入微信交流群

## 特性

1. 访问统一：如论是什么IP资源供应商，业务代码均接入ProxyCompose，IP资源供应商的变动不会影响业务代码
2. 池间路由：对于多个IP资源供应商，支持浮动流量比例动态调控，即根据IP池健康评估，弹性伸缩各个IP池的流量。某个IP池挂了不影响整体业务
3. 协议转换：你可以使用ProxyCompose实现http/https/socks5几种代理协议的转换，这样即使采购的代理资源仅支持socks5，也能转换为https代理
4. 池化加速：ProxyCompose内置了一个高效的IP池模块，可以对IP资源的访问进行探测、评分、连接池等工作，提高IP资源使用成功率

## 使用

### 构建

- 安装Java
- 安装maven
- Linux/mac下，执行脚本：``build.sh``，得到文件``target/proxy-compose.zip``即为产出文件
- 配置： 请根据实际情况配置代理资源 ``conf/config.ini``
- 运行脚本：``bin/ProxyComposed.sh`` 或 ``bin/ProxyComposed.bat``
- 代理测试：``curl -x iinti:iinti@127.0.0.1:36000 https://www.baidu.com/``

**如不方便构建，可以使用我们构建好的发布包:[https://oss.iinti.cn/malenia/proxy-compose.zip](https://oss.iinti.cn/malenia/proxy-compose.zip)**

### 最简配置

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

### 完整配置

```ini
[global]
# 开启debug将会有更加丰富的日志
debug=false
# 对代理IP质量进行探测的URL
proxyHttpTestURL = https://iinti.cn/conn/getPublicIp?scene=proxy_compose
# 代理服务器启动端口，本系统将会在配置端口范围连续启动多个代理服务器
mappingSpace=36000-36010
# 是否启用随机隧道，启用随机隧道之后，每次代理请求将会使用随机的IP出口
randomTurning=false
# 是否启用池间路由，池间路由支持根据IP池的健康状态在多个IP池之间动态调整比例
enableFloatIpSourceRatio=true
# failover次数，即系统为失败的代理转发进行的充实
maxFailoverCount=3
# 代理连接超时时间
handleSharkConnectionTimeout=5000
# 鉴权用户，即用户连接到proxy_compose的鉴权
auth_username=iinti
# 鉴权密码
auth_password=iinti
# 使用IP白名单，或者IP端的方式进行鉴权
auth_white_ips=122.23.43.0/24,29.23.45.65

# 定义IP资源，即从IP供应商采购的IP资源,要求至少配置一个IP资源
# section 要求以 《source:》开始
[source:dailiyun]
# 本资源是否启用，如果希望临时关闭本资源，但是不希望删除配置，可以使用本开关
enable=true
# IP资源下载连接
loadURL=http://修改这里.user.xiecaiyun.com/api/proxies?action=getText&key=修改这里&count=修改这里&word=&rand=false&norepeat=false&detail=false&ltime=0
# IP资源格式，目前支持plain，json两种格式，其中json格式需要满足json格式要求 cn.iinti.proxycompose.resource.ProxyIp
resourceFormat=plain
# Ip供应提供的代理账户（如果是白名单，后者无鉴权，则无需配置）
upstreamAuthUser=修改这里
# Ip供应提供的代密码
upstreamAuthPassword=修改这里
# IP池大小，非常重要，此字段为您的IP供应商单次提取返回的节点数
poolSize=10
# 本IP资源池是否需要探测IP质量，如开启，则IP需要被验证可用后方可加入IP池
needTest=true
# IP资源下载间隔时间，单位秒
reloadInterval=240
# IP资源入库后最长存活时间，单位秒，达到此时间后，对应IP资源将会从IP池中移除，除非被重新下载到IP池中
maxAlive=300
# 当前IP资源支持的代理协议（建议至少选择支持socks5的资源）
supportProtocol=socks5,https,http
# 连接池连接空转时间，单位秒，IP池将会提前创建到代理真实代理服务器的连接，给业务使用提供加速功能
connIdleSeconds=20
# 提前创建连接的时间间隔，单位秒
makeConnInterval=20
# 当前IP池在池间流量比例，当存在多个Ip资源配置时，本配置有效，即业务按照此比例对多个IP池进行流量情切
ratio=1
```

## 特别说明

ComposeProxy本身能做的工作非常丰富，更多想象空间可以参考我们对应的商业分支：[malenia](https://malenia.iinti.cn/malenia-doc/)
用户如提交任何新的功能（即使和商业分支重叠）均可以被接收