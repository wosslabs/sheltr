package com.sheltr.config;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: finds already-created singleton beans whose {@code toString()} or whose own
 * instance fields (shallow, including superclasses) contain a secret. Does not create beans.
 */
final class BeanSecrets {

    private BeanSecrets() {
    }

    static List<String> beansExposing(ConfigurableListableBeanFactory beanFactory, String secret) {
        List<String> offenders = new ArrayList<>();
        for (String name : beanFactory.getSingletonNames()) {
            Object bean = beanFactory.getSingleton(name);
            if (bean == null) {
                continue;
            }
            if (safeToString(bean).contains(secret) || fieldsContain(bean, secret)) {
                offenders.add(name + " (" + bean.getClass().getName() + ")");
            }
        }
        return offenders;
    }

    private static boolean fieldsContain(Object bean, String secret) {
        for (Class<?> type = bean.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(bean);
                    if (value instanceof CharSequence text && text.toString().contains(secret)) {
                        return true;
                    }
                    if (value instanceof char[] chars && new String(chars).contains(secret)) {
                        return true;
                    }
                    if (value != null && !(value instanceof CharSequence) && safeToString(value).contains(secret)) {
                        return true;
                    }
                } catch (RuntimeException | IllegalAccessException inaccessible) {
                    // Module-encapsulated JDK field: cannot hold an application secret we bound.
                }
            }
        }
        return false;
    }

    private static String safeToString(Object value) {
        try {
            return String.valueOf(value);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
