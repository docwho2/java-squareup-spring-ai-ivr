package cloud.cleo.squareup;


import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Allow non-Spring managed classes to obtain beans.
 * 
 * @author sjensen
 */
@Component
public class SpringContext implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        SpringContext.context = ctx;
    }

    public static <T> T getBean(Class<T> type) {
        return context.getBean(type);
    }

    public static <T> T getBean(String name, Class<T> type) {
        return context.getBean(name, type);
    }
}
