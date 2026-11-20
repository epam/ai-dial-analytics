package com.epam.aidial.analytics.web.openapi;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every top-level {@code @RestController} of the application, found by a classpath scan rather than from a Spring
 * context, so a controller some context leaves out is still found. Classes are filtered on their bytecode metadata
 * and loaded without initialisation, so the scan runs no static initializer (a test support class that starts a
 * container, for one), and a controller that cannot be loaded fails the caller instead of being skipped.
 */
public final class ApplicationControllers {

    private static final String APPLICATION_PACKAGE = "com/epam/aidial/analytics";

    private ApplicationControllers() {
    }

    /** The controllers, sorted by class name. */
    public static List<Class<?>> scan() throws IOException, ClassNotFoundException {
        final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        final ClassLoader loader = ApplicationControllers.class.getClassLoader();
        final List<Class<?>> controllers = new ArrayList<>();
        for (final Resource resource : resolver.getResources("classpath*:" + APPLICATION_PACKAGE + "/**/*.class")) {
            final MetadataReader reader = readers.getMetadataReader(resource);
            if (reader.getClassMetadata().hasEnclosingClass() || !reader.getAnnotationMetadata().isAnnotated(RestController.class.getName())) {
                continue;
            }
            controllers.add(Class.forName(reader.getClassMetadata().getClassName(), false, loader));
        }
        controllers.sort(Comparator.comparing(Class::getName));
        return controllers;
    }
}
