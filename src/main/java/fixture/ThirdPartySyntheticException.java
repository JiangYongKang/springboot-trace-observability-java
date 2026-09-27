package fixture;

/**
 * 模拟非白名单第三方包下的异常：包名 {@code fixture} 不在
 * observability.allowed-exception-prefixes 内，指标标签必须折叠为 OtherException。
 * 放在主代码而非测试夹具，是为了让运行中的演示接口 /boom 也能触发该场景。
 */
public class ThirdPartySyntheticException extends RuntimeException {
    public ThirdPartySyntheticException(String message) {
        super(message);
    }
}
