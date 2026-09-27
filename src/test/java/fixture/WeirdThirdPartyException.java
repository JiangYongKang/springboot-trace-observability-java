package fixture;

/**
 * 模拟非白名单第三方包下的异常：包名不匹配任何允许前缀，
 * 指标标签必须折叠为 OtherException。
 */
public class WeirdThirdPartyException extends RuntimeException {
    public WeirdThirdPartyException(String message) {
        super(message);
    }
}
