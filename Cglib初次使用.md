```java
import net.sf.cglib.core.DefaultNamingPolicy;
import net.sf.cglib.proxy.*;

import java.lang.reflect.Method;

public class App {
    public static class CallBackA implements MethodInterceptor {

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            System.out.println("before callbackA ..");
            Object result = methodProxy.invokeSuper(o, objects);
            System.out.println("after callbackA ..");
            return result;
        }
    }

    public static class CallBackB implements MethodInterceptor {

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            System.out.println("before callbackB ..");
            Object result = methodProxy.invokeSuper(o, objects);
            System.out.println("after callbackB ..");
            return result;
        }
    }

    public static class CustomCallBackFilter implements CallbackFilter {

        @Override
        public int accept(Method method) {
            return 0;
        }
    }

    public static class Origin {
        public void methodA() {
            System.out.println("this is methodA");
        }

        public void methodB() {
            System.out.println("this is methodB");
        }
    }

    public static class CustomNamingPolicy extends DefaultNamingPolicy {
        @Override
        protected String getTag() {
            return "ByCustom";
        }
    }

    public static void main(String[] args) {
        Enhancer enhancer = new Enhancer();
        //设置父类
        enhancer.setSuperclass(Origin.class);
        //设置需要织入的逻辑
        enhancer.setCallbacks(new Callback[] { new CallBackA(), new CallBackB() });
        //CustomCallBackFilter的accept方法返回int值代表需要使用上面第n个Callback来做拦截
        //比如这里返回0就代表使用上面Callback数组第0个，也就是CallBackA，如果返回1，则使用上面的CallBackB
        //返回值绝对不能超出CallBack数组长度-1
        enhancer.setCallbackFilter(new CustomCallBackFilter());
        //setNamingPolicy就是给生成的子类命名用的
        enhancer.setNamingPolicy(new CustomNamingPolicy());
        // 创建代理对象
        Origin proxy = (Origin) enhancer.create();
        proxy.methodA();
        proxy.methodB();
        System.out.println(proxy.getClass().getCanonicalName());
    }
}
```
