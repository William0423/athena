package com.timeyang.athena.utill;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * @author https://github.com/chaokunyang
 */
public class ReflectionUtils {

    public static Object invokeMethod(Object target, String methodName) {
        return invokeMethod(target, methodName, null);
    }

    /**
     * Invoke specified method in target class or super class, regardless of access level of method
     *
     * @param target target object
     * @param methodName the name of the method
     * @param parameterTypes parameter type array
     * @param params method params
     * @return method invoke result
     */
    public static Object invokeMethod(Object target, String methodName, Class<?>[] parameterTypes, Object... params) {
        Class<?> clazz = target.getClass();
        do {
            try {
                // getMethods() return only public methods, though includes super methods
                // getDeclaredMethods() return current class all methods, include non public methods, but doesn't include super methods, so clazz = clazz.getSuperclass() is needed.
                Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);

                return method.invoke(target, params);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
                if(clazz == null)
                    throw new RuntimeException(new NoSuchMethodException(target.getClass()
                            + " and all its super class doesn't have " + methodName
                            + " with parameterTypes: " + Arrays.toString(parameterTypes)));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        } while (true);
    }

}
