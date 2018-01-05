package ch.cyberduck.core.dav;

/*
 * Copyright (c) 2002-2011 David Kocher. All rights reserved.
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

import ch.cyberduck.core.AttributedList;
import ch.cyberduck.core.ConnectionCallback;
import ch.cyberduck.core.DisabledListProgressListener;
import ch.cyberduck.core.Host;
import ch.cyberduck.core.HostKeyCallback;
import ch.cyberduck.core.HostPasswordStore;
import ch.cyberduck.core.HostUrlProvider;
import ch.cyberduck.core.ListProgressListener;
import ch.cyberduck.core.ListService;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.LoginCallback;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.ProtocolFactory;
import ch.cyberduck.core.Scheme;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.ConnectionCanceledException;
import ch.cyberduck.core.exception.ListCanceledException;
import ch.cyberduck.core.exception.LoginCanceledException;
import ch.cyberduck.core.features.*;
import ch.cyberduck.core.http.HttpExceptionMappingService;
import ch.cyberduck.core.http.HttpSession;
import ch.cyberduck.core.http.PreferencesRedirectCallback;
import ch.cyberduck.core.http.RedirectCallback;
import ch.cyberduck.core.preferences.Preferences;
import ch.cyberduck.core.preferences.PreferencesFactory;
import ch.cyberduck.core.proxy.ProxyFinder;
import ch.cyberduck.core.shared.DefaultHomeFinderService;
import ch.cyberduck.core.shared.DefaultTouchFeature;
import ch.cyberduck.core.ssl.DefaultX509KeyManager;
import ch.cyberduck.core.ssl.DisabledX509TrustManager;
import ch.cyberduck.core.ssl.ThreadLocalHostnameDelegatingTrustManager;
import ch.cyberduck.core.ssl.X509KeyManager;
import ch.cyberduck.core.ssl.X509TrustManager;
import ch.cyberduck.core.threading.CancelCallback;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.log4j.Logger;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.MessageFormat;

import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.handler.ValidatingResponseHandler;
import com.github.sardine.impl.handler.VoidResponseHandler;

public class DAVSession extends HttpSession<DAVClient> {
    private static final Logger log = Logger.getLogger(DAVSession.class);

    private RedirectCallback redirect
        = new PreferencesRedirectCallback();

    private final Preferences preferences
        = PreferencesFactory.get();

    public DAVSession(final Host host) {
        super(host, new ThreadLocalHostnameDelegatingTrustManager(new DisabledX509TrustManager(), host.getHostname()), new DefaultX509KeyManager());
    }

    public DAVSession(final Host host, final X509TrustManager trust, final X509KeyManager key) {
        super(host, new ThreadLocalHostnameDelegatingTrustManager(trust, host.getHostname()), key);
    }

    public DAVSession(final Host host, final RedirectCallback redirect) {
        super(host, new ThreadLocalHostnameDelegatingTrustManager(new DisabledX509TrustManager(), host.getHostname()), new DefaultX509KeyManager());
        this.redirect = redirect;
    }

    public DAVSession(final Host host, final X509TrustManager trust, final X509KeyManager key, final RedirectCallback redirect) {
        super(host, new ThreadLocalHostnameDelegatingTrustManager(trust, host.getHostname()), key);
        this.redirect = redirect;
    }

    public DAVSession(final Host host, final X509TrustManager trust, final X509KeyManager key, final SocketFactory socketFactory) {
        super(host, new ThreadLocalHostnameDelegatingTrustManager(trust, host.getHostname()), key, socketFactory);
    }

    public DAVSession(final Host host, final X509TrustManager trust, final X509KeyManager key, final ProxyFinder proxy) {
        super(host, new ThreadLocalHostnameDelegatingTrustManager(trust, host.getHostname()), key, proxy);
    }

    public DAVSession(final Host host, final X509TrustManager trust, final X509KeyManager key, final SocketFactory socketFactory, final RedirectCallback redirect) {
        super(host, new ThreadLocalHostnameDelegatingTrustManager(trust, host.getHostname()), key, socketFactory);
        this.redirect = redirect;
    }

    @Override
    public DAVClient connect(final HostKeyCallback key, final LoginCallback prompt) throws BackgroundException {
        // Always inject new pool to builder on connect because the pool is shutdown on disconnect
        final HttpClientBuilder pool = builder.build(this, prompt);
        pool.setRedirectStrategy(new DAVRedirectStrategy(redirect));
        return new DAVClient(new HostUrlProvider(false).get(host), pool);
    }

    @Override
    protected void logout() throws BackgroundException {
        try {
            client.shutdown();
        }
        catch(IOException e) {
            throw new HttpExceptionMappingService().map(e);
        }
    }

    @Override
    public void login(final HostPasswordStore keychain, final LoginCallback prompt, final CancelCallback cancel) throws BackgroundException {
        client.setCredentials(host.getCredentials().getUsername(), host.getCredentials().getPassword(),
            // Windows credentials. Provide empty string for NTLM domain by default.
            preferences.getProperty("webdav.ntlm.workstation"),
            preferences.getProperty("webdav.ntlm.domain"));
        if(preferences.getBoolean("webdav.basic.preemptive")) {
            // Enable preemptive authentication. See HttpState#setAuthenticationPreemptive
            client.enablePreemptiveAuthentication(host.getHostname(),
                host.getPort(),
                host.getPort(),
                Charset.forName(preferences.getProperty("http.credentials.charset"))
            );
        }
        else {
            client.disablePreemptiveAuthentication();
        }
        if(host.getCredentials().isPassed()) {
            log.warn(String.format("Skip verifying credentials with previous successful authentication event for %s", this));
            return;
        }
        try {
            final Path home = new DefaultHomeFinderService(this).find();
            try {
                client.execute(new HttpHead(new DAVPathEncoder().encode(home)), new VoidResponseHandler());
            }
            catch(SardineException e) {
                switch(e.getStatusCode()) {
                    case HttpStatus.SC_FORBIDDEN:
                    case HttpStatus.SC_NOT_FOUND:
                    case HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE:
                    case HttpStatus.SC_METHOD_NOT_ALLOWED:
                        log.warn(String.format("Failed HEAD request to %s with %s. Retry with PROPFIND.",
                            host, e.getResponsePhrase()));
                        cancel.verify();
                        // Possibly only HEAD requests are not allowed
                        this.getFeature(ListService.class).list(home, new DisabledListProgressListener() {
                            @Override
                            public void chunk(final Path parent, final AttributedList<Path> list) throws ListCanceledException {
                                try {
                                    cancel.verify();
                                }
                                catch(ConnectionCanceledException e) {
                                    throw new ListCanceledException(list, e);
                                }
                            }
                        });
                        break;
                    case HttpStatus.SC_BAD_REQUEST:
                        if(preferences.getBoolean("webdav.basic.preemptive")) {
                            log.warn(String.format("Disable preemptive authentication for %s due to failure %s",
                                host, e.getResponsePhrase()));
                            cancel.verify();
                            client.disablePreemptiveAuthentication();
                            client.execute(new HttpHead(new DAVPathEncoder().encode(home)), new VoidResponseHandler());
                        }
                        else {
                            throw new DAVExceptionMappingService().map(e);
                        }
                        break;
                    default:
                        throw new DAVExceptionMappingService().map(e);
                }
            }
        }
        catch(SardineException e) {
            throw new DAVExceptionMappingService().map(e);
        }
        catch(IOException e) {
            throw new HttpExceptionMappingService().map(e);
        }
    }

    @Override
    public boolean alert(final ConnectionCallback callback) throws BackgroundException {
        if(super.alert(callback)) {
            // Propose protocol change if HEAD request redirects to HTTPS
            final Path home = new DefaultHomeFinderService(this).find();
            try {
                final RequestConfig context = client.context().getRequestConfig();
                final HttpHead request = new HttpHead(new DAVPathEncoder().encode(home));
                request.setConfig(RequestConfig.copy(context).setRedirectsEnabled(false).build());
                final Header location = client.execute(request, new ValidatingResponseHandler<Header>() {
                    @Override
                    public Header handleResponse(final HttpResponse response) throws IOException {
                        if(response.getStatusLine().getStatusCode() == HttpStatus.SC_MOVED_PERMANENTLY) {
                            return response.getFirstHeader(HttpHeaders.LOCATION);
                        }
                        return null;
                    }
                });
                // Reset default redirect configuration in context
                client.context().setRequestConfig(RequestConfig.copy(context).setRedirectsEnabled(true).build());
                if(null != location) {
                    final URL url = new URL(location.getValue());
                    if(StringUtils.equals(Scheme.https.name(), url.getProtocol())) {
                        try {
                            callback.warn(host,
                                MessageFormat.format(LocaleFactory.localizedString("Unsecured {0} connection", "Credentials"), host.getProtocol().getName()),
                                MessageFormat.format("{0} {1}.", MessageFormat.format(LocaleFactory.localizedString("The server supports encrypted connections. Do you want to switch to {0}?", "Credentials"),
                                    new DAVSSLProtocol().getName()), LocaleFactory.localizedString("Please contact your web hosting service provider for assistance", "Support")),
                                LocaleFactory.localizedString("Continue", "Credentials"),
                                LocaleFactory.localizedString("Change", "Credentials"),
                                String.format("connection.unsecure.%s", host.getHostname()));
                            // Continue chosen. Login using plain FTP.
                        }
                        catch(LoginCanceledException e) {
                            // Protocol switch
                            host.setHostname(url.getHost());
                            host.setProtocol(ProtocolFactory.get().forScheme(Scheme.davs));
                            return false;
                        }
                    }
                }
                // Continue with default alert
            }
            catch(SardineException e) {
                // Ignore failure
                log.warn(String.format("Ignore failed HEAD request to %s with %s.", host, e.getResponsePhrase()));
            }
            catch(IOException e) {
                throw new HttpExceptionMappingService().map(e);
            }
            return preferences.getBoolean("webdav.basic.preemptive");
        }
        return false;
    }

    @Override
    public AttributedList<Path> list(final Path directory, final ListProgressListener listener) throws BackgroundException {
        return new DAVListService(this).list(directory, listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T _getFeature(final Class<T> type) {
        if(type == Directory.class) {
            return (T) new DAVDirectoryFeature(this);
        }
        if(type == Read.class) {
            return (T) new DAVReadFeature(this);
        }
        if(type == Write.class) {
            return (T) new DAVWriteFeature(this);
        }
        if(type == Upload.class) {
            return (T) new DAVUploadFeature(new DAVWriteFeature(this));
        }
        if(type == Delete.class) {
            return (T) new DAVDeleteFeature(this);
        }
        if(type == Move.class) {
            return (T) new DAVMoveFeature(this);
        }
        if(type == Headers.class) {
            return (T) new DAVMetadataFeature(this);
        }
        if(type == Metadata.class) {
            return (T) new DAVMetadataFeature(this);
        }
        if(type == Copy.class) {
            return (T) new DAVCopyFeature(this);
        }
        if(type == Find.class) {
            return (T) new DAVFindFeature(this);
        }
        if(type == AttributesFinder.class) {
            return (T) new DAVAttributesFinderFeature(this);
        }
        if(type == Timestamp.class) {
            return (T) new DAVTimestampFeature(this);
        }
        if(type == Quota.class) {
            return (T) new DAVQuotaFeature(this);
        }
        if(type == Lock.class) {
            return (T) new DAVLockFeature(this);
        }
        if(type == Touch.class) {
            return (T) new DefaultTouchFeature(new DAVUploadFeature(new DAVWriteFeature(this)));
        }
        return super._getFeature(type);
    }

}
