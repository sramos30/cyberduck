package ch.cyberduck.ui.action;

/*
 * Copyright (c) 2002-2010 David Kocher. All rights reserved.
 *
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

import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.exception.BackgroundException;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @version $Id$
 */
public abstract class Worker<T> {

    private AtomicBoolean canceled
            = new AtomicBoolean();

    protected String toString(List<Path> files) {
        if(files.isEmpty()) {
            return LocaleFactory.localizedString("None");
        }
        final StringBuilder name = new StringBuilder();
        name.append(files.get(0).getName());
        if(files.size() > 1) {
            name.append("… (").append(files.size()).append(")");
        }
        return name.toString();
    }

    public T run() throws BackgroundException {
        throw new BackgroundException("Not supported");
    }

    public void cleanup(T result) {
        //
    }

    public String getActivity() {
        return LocaleFactory.localizedString("Unknown");
    }

    public void cancel() {
        canceled.set(true);
    }

    public boolean isCanceled() {
        return canceled.get();
    }
}
