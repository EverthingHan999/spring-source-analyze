package hjz.spring.demo.v1;

import hjz.spring.demo.annotation.GPAutowired;
import hjz.spring.demo.annotation.GPController;
import hjz.spring.demo.annotation.GPRequestMapping;
import hjz.spring.demo.annotation.GPRequestParam;
import hjz.spring.demo.annotation.GPService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class DispatcherServlet extends HttpServlet {

    private Properties contextConfig = new Properties();
    private List<String> classNames = new ArrayList<>();
    private Map<String, Object> ioc = new HashMap<>();
    private Map<String, Method> handlerMapping = new HashMap<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatch(req,resp);
        } catch (Exception e) {
            resp.getWriter().write("500 error!");
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");
        if (!handlerMapping.containsKey(url)) {
            resp.getWriter().write("404 not fund");
        }

        Method method = handlerMapping.get(url);

        //建立形参位置和名字的映射
        HashMap<String, Integer> paramIndexMapping = new HashMap<>();

        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof GPRequestParam) {
                    String paramName = ((GPRequestParam) annotation).value();
                    if (!"".equals(paramName.trim())) {
                        paramIndexMapping.put(paramName, i);
                    }
                }
            }
        }

        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType == HttpServletRequest.class || parameterType == HttpServletResponse.class) {
                paramIndexMapping.put(parameterType.getName(), i);
            }
        }

        //根据 参数位置匹配参数名字，从url中获取参数值
        Object[] paramValues = new Object[parameterTypes.length];
        Map<String, String[]> parameterMap = req.getParameterMap();
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String value = Arrays.toString(entry.getValue()).replaceAll("\\[|\\]", "").replaceAll("\\s", "");
            if (!paramIndexMapping.containsKey(entry.getKey())){continue;}

            int index = paramIndexMapping.get(entry.getKey());
            //涉及到类型强转
            paramValues[index] = value;
        }

        if(paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
            int index = paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[index] = req;
        }

        if(paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
            int index = paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[index] = resp;
        }
        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
        //3、组成动态实际参数列表，传给反射调用
        method.invoke(ioc.get(beanName),paramValues);
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        //加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        //扫描相关的类
        doScanner((String)contextConfig.get("scanPackage"));
        //实例化ioc容器
        doInstanceIoc();
        //完成依赖注入
        doAutowired();
        //mvc映射初始化
        doInitHandlerMapping();

        System.out.println("dispatcherServlet init finsh!");
    }

    private void doInitHandlerMapping() {
        if (ioc.isEmpty()){return;}

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            if (!entry.getValue().getClass().isAnnotationPresent(GPController.class)){continue;}

            Class<?> clazz = entry.getValue().getClass();
            String baseUrl = "";
            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping annotation = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = annotation.value().trim();
            }

            //迭代方法
            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(GPRequestMapping.class)){return;}

                GPRequestMapping annotation = method.getAnnotation(GPRequestMapping.class);
                // //do//query
                String url = ("/" + baseUrl + "/" + annotation.value().trim()).replaceAll("/+", "/");
                handlerMapping.put(url, method);
                System.out.println("Mapped : "+url+"--->"+method);
            }
        }
    }

    private void doAutowired() {
        if (ioc.isEmpty()){return;}
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            //获取类的所有属性包括public private
            for (Field field : entry.getValue().getClass().getDeclaredFields()) {
                if (!field.isAnnotationPresent(GPAutowired.class)){continue;}

                GPAutowired annotation = field.getAnnotation(GPAutowired.class);
                String beanName = annotation.value().trim();
                if ("".equals(beanName)) {
                    beanName = field.getType().getName();
                }
                //强制访问 强吻
                field.setAccessible(true);

                try {
                    field.set(entry.getValue(),ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doInstanceIoc() {
        if (classNames.isEmpty()){return;}

        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);
                if (clazz.isAnnotationPresent(GPController.class)) {
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    Object bean = clazz.newInstance();
                    ioc.put(beanName, bean);
                } else if (clazz.isAnnotationPresent(GPService.class)) {
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    //如果出现多个包下有重名的类优先使用自定义的别名
                    GPService annotation = clazz.getAnnotation(GPService.class);
                    if (!"".equals(annotation.value())) {
                        beanName = annotation.value();
                    }
                    Object bean = clazz.newInstance();
                    ioc.put(beanName, bean);

                    //如果该接口实现了接口，则将其接口的实现类全部初始化成他本身
                    for (Class<?> i : clazz.getInterfaces()) {
                        if (ioc.containsKey(toLowerFirstCase(i.getName()))) {
                            throw new Exception("The " + i.getName() + " is exists,please use alies!!");
                        }
                        ioc.put(toLowerFirstCase(i.getName()), bean);
                    }
                } else {
                    continue;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String toLowerFirstCase(String simpleName) {
        char [] chars = simpleName.toCharArray();
        chars[0] += 32;     //利用了ASCII码，大写字母和小写相差32这个规律
        return String.valueOf(chars);
    }

    private void doScanner(String scanPackage) {
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replace(".", "/"));
        File classPath = new File(url.getFile());

        for (File file : classPath.listFiles()) {
            if (file.isDirectory()) {
                //是包，则递归扫描子包
                doScanner(scanPackage + "." + file.getName());
            } else {
                //取反 减少if-else
                if (!file.getName().endsWith(".class")){continue;}

                String className = scanPackage + "." + file.getName().replace(".class", "");
                classNames.add(className);
            }
        }
    }

    private void doLoadConfig(String contextConfigLocation) {
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (null != is) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
