package ch.cyberduck.core.editor;

/*
 * Copyright (c) 2012 David Kocher. All rights reserved.
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * dkocher@cyberduck.ch
 */

import ch.cyberduck.core.Native;
import ch.cyberduck.core.local.Local;
import ch.cyberduck.core.local.LocalFactory;
import ch.cyberduck.ui.cocoa.application.NSWorkspace;
import ch.cyberduck.ui.cocoa.foundation.NSBundle;
import ch.cyberduck.ui.cocoa.foundation.NSDictionary;
import ch.cyberduck.ui.cocoa.foundation.NSEnumerator;
import ch.cyberduck.ui.cocoa.foundation.NSObject;

import org.apache.commons.collections.map.AbstractLinkedMap;
import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.rococoa.Rococoa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @version $Id$
 */
public final class LaunchServicesApplicationFinder implements ApplicationFinder {
    private static final Logger log = Logger.getLogger(ApplicationFinder.class);

    public static void register() {
        ApplicationFinderFactory.addFactory(Factory.NATIVE_PLATFORM, new Factory());
    }

    private static class Factory extends ApplicationFinderFactory {
        @Override
        protected ApplicationFinder create() {
            return new LaunchServicesApplicationFinder();
        }
    }

    private static final Object workspace = new Object();

    private LaunchServicesApplicationFinder() {
        Native.load("LaunchServicesApplicationFinder");
    }

    /**
     * Uses LSGetApplicationForInfo
     *
     * @param extension File extension
     * @return Null if not found
     */
    private native String findForType(String extension);

    /**
     * Uses LSCopyAllRoleHandlersForContentType
     *
     * @param extension File extension
     * @return Empty array if none found
     */
    private native String[] findAllForType(String extension);

    /**
     * Caching map between application bundle identifier and
     * display name of application
     */
    private static Map<String, Application> applicationNameCache
            = Collections.<String, Application>synchronizedMap(new LRUMap(20) {
        @Override
        protected boolean removeLRU(AbstractLinkedMap.LinkEntry entry) {
            log.debug("Removing from cache:" + entry);
            return true;
        }
    });

    /**
     *
     */
    private static Map<String, Application> defaultApplicationCache
            = Collections.<String, Application>synchronizedMap(new LRUMap(20) {
        @Override
        protected boolean removeLRU(AbstractLinkedMap.LinkEntry entry) {
            log.debug("Removing from cache:" + entry);
            return true;
        }
    });

    /**
     * Caching map between application bundle identifiers and
     * file type extensions.
     */
    private static Map<String, List<Application>> defaultApplicationListCache
            = Collections.<String, List<Application>>synchronizedMap(new LRUMap(20) {
        @Override
        protected boolean removeLRU(AbstractLinkedMap.LinkEntry entry) {
            log.debug("Removing from cache:" + entry);
            return true;
        }
    });

    @Override
    public List<Application> findAll(final Local file) {
        final String extension = file.getExtension();
        if(StringUtils.isEmpty(extension)) {
            return Collections.emptyList();
        }
        if(!defaultApplicationListCache.containsKey(extension)) {
            final List<Application> applications = new ArrayList<Application>();
            for(String identifier : this.findAllForType(extension)) {
                applications.add(this.find(identifier));
            }
            // Because of the different API used the default opening application may not be included
            // in the above list returned. Always add the default application anyway.
            final Application defaultApplication = this.find(file);
            if(null != defaultApplication) {
                if(!applications.contains(defaultApplication)) {
                    applications.add(defaultApplication);
                }
            }
            defaultApplicationListCache.put(extension, applications);
        }
        return defaultApplicationListCache.get(extension);
    }


    /**
     * The default application for this file as set by the launch services
     *
     * @return The bundle identifier of the default application to open the
     *         file of this type or null if unknown
     */
    @Override
    public Application find(final Local file) {
        final String extension = file.getExtension();
        if(!defaultApplicationCache.containsKey(extension)) {
            if(StringUtils.isEmpty(extension)) {
                return null;
            }
            final String path = this.findForType(extension);
            if(StringUtils.isEmpty(path)) {
                defaultApplicationCache.put(extension, null);
            }
            else {
                final NSBundle bundle = NSBundle.bundleWithPath(path);
                if(null == bundle) {
                    log.error("Loading bundle failed:" + path);
                    defaultApplicationCache.put(extension, null);
                }
                else {
                    defaultApplicationCache.put(extension, this.find(bundle.bundleIdentifier()));
                }
            }
        }
        return defaultApplicationCache.get(extension);
    }

    /**
     * Determine the human readable application name for a given bundle identifier.
     *
     * @param bundleIdentifier Bundle identifier
     * @return Application human readable name
     */
    @Override
    public Application find(final String bundleIdentifier) {
        if(!applicationNameCache.containsKey(bundleIdentifier)) {
            if(log.isDebugEnabled()) {
                log.debug(String.format("Find application for %s", bundleIdentifier));
            }
            synchronized(workspace) {
                final String path = NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier(bundleIdentifier);
                String name = null;
                if(StringUtils.isNotBlank(path)) {
                    NSBundle app = NSBundle.bundleWithPath(path);
                    if(null == app) {
                        log.error("Loading bundle failed:" + path);
                    }
                    else {
                        NSDictionary dict = app.infoDictionary();
                        if(null == dict) {
                            log.error("Loading application dictionary failed:" + path);
                            applicationNameCache.put(bundleIdentifier, null);
                            return null;
                        }
                        else {
                            final NSObject bundlename = dict.objectForKey("CFBundleName");
                            if(null == bundlename) {
                                log.warn(String.format("No CFBundleName for %s", bundleIdentifier));
                            }
                            else {
                                name = bundlename.toString();
                            }
                        }
                    }
                    if(null == name) {
                        name = FilenameUtils.removeExtension(LocalFactory.createLocal(path).getDisplayName());
                    }
                }
                else {
                    log.warn(String.format("Cannot determine installation path for %s", bundleIdentifier));
                }
                applicationNameCache.put(bundleIdentifier, new Application(bundleIdentifier, name));
            }
        }
        return applicationNameCache.get(bundleIdentifier);
    }

    @Override
    public boolean isInstalled(final Application application) {
        synchronized(workspace) {
            return NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier(
                    application.getIdentifier()) != null;
        }
    }

    /**
     * @return True if the editor application is running
     */
    @Override
    public boolean isOpen(final Application application) {
        synchronized(workspace) {
            final NSEnumerator apps = NSWorkspace.sharedWorkspace().launchedApplications().objectEnumerator();
            NSObject next;
            while(((next = apps.nextObject()) != null)) {
                NSDictionary app = Rococoa.cast(next, NSDictionary.class);
                final NSObject identifier = app.objectForKey("NSApplicationBundleIdentifier");
                if(identifier.toString().equals(application.getIdentifier())) {
                    if(log.isDebugEnabled()) {
                        log.debug(String.format("Found open application %s", application.getIdentifier()));
                    }
                    return true;
                }
            }
            return false;
        }
    }
}