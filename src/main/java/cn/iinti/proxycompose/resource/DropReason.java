package cn.iinti.proxycompose.resource;

public enum DropReason {
    /**
     * IP入库前连通性检查失败
     */
    IP_TEST_FAILED,
    /**
     * 使用期间，发现ip资源服务不可用。
     */
    IP_SERVER_UNAVAILABLE,
    /**
     * 使用期间动态判断ip资源质量差
     */
    IP_QUALITY_BAD,
    /**
     * IP没有问题，但空转无使用，直到更加新的IP资源入库替换本资源
     */
    IP_IDLE_POOL_OVERFLOW,
    /**
     * IP没有问题，但使用时间达到用户配置/指定的存活时间而被自动下线
     */
    IP_ALIVE_TIME_REACHED,
    /**
     * 管理员关闭整个IP池资源，此时下线本ip池中所有的IP资源
     */
    IP_RESOURCE_CLOSE,
}
