/*
 * Copyright (c) 2000, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import sun.net.ConnectionResetException;
import sun.net.NetHooks;
import sun.net.ext.ExtendedSocketOptions;
import sun.net.util.SocketExceptions;

/**
 * An implementation of SocketChannels
 */

class InetSocketChannelImpl extends SocketChannelImpl
{
    // set true when exclusive binding is on and SO_REUSEADDR is emulated
    private boolean isReuseAddress;

    // the protocol family requested by the user, or Net.UNSPEC if not specified
    private final ProtocolFamily family;

    // Constructor for normal connecting sockets
    //
    InetSocketChannelImpl(SelectorProvider sp) throws IOException {
        this(sp, Net.isIPv6Available()
                ? StandardProtocolFamily.INET6
                : StandardProtocolFamily.INET);
    }

    InetSocketChannelImpl(SelectorProvider sp, ProtocolFamily family) throws IOException {
        super(sp, Net.socket(family, true), false);
        this.family = family;
    }

    InetSocketChannelImpl(SelectorProvider sp, FileDescriptor fd, boolean bound)
        throws IOException
    {
        super(sp, fd, bound);
        this.family = Net.isIPv6Available()
                ? StandardProtocolFamily.INET6
                : StandardProtocolFamily.INET;
    }

    @Override
    SocketAddress localAddressImpl(FileDescriptor fd) throws IOException {
        return Net.localAddress(fd);
    }

    // Constructor for sockets obtained from server sockets
    //
    InetSocketChannelImpl(SelectorProvider sp, FileDescriptor fd, InetSocketAddress isa)
        throws IOException
    {
        super(sp, fd, isa);
        this.family = Net.isIPv6Available()
                ? StandardProtocolFamily.INET6
                : StandardProtocolFamily.INET;
    }

    @Override
    SocketAddress getRevealedLocalAddress(SocketAddress address) {
        return Net.getRevealedLocalAddress((InetSocketAddress)address);
    }


    @SuppressWarnings("unchecked")
    <T> T getOptionSpecial(SocketOption<T> name) throws IOException {
        if (name == StandardSocketOptions.SO_REUSEADDR && Net.useExclusiveBind()) {
            // SO_REUSEADDR emulated when using exclusive bind
            return (T)Boolean.valueOf(isReuseAddress);
        }

        // special handling for IP_TOS: always return 0 when IPv6
        if (name == StandardSocketOptions.IP_TOS) {
            ProtocolFamily family = Net.isIPv6Available() ?
                StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
            return (T) Net.getSocketOption(getFD(), family, name);
        }
        return null;
    }

    <T> boolean setOptionSpecial(SocketOption<T> name, T value) throws IOException {
        if (name == StandardSocketOptions.IP_TOS) {
            ProtocolFamily family = Net.isIPv6Available() ?
                StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
            Net.setSocketOption(getFD(), family, name, value);
            return true;
        }

        if (name == StandardSocketOptions.SO_REUSEADDR && Net.useExclusiveBind()) {
            // SO_REUSEADDR emulated when using exclusive bind
            isReuseAddress = (Boolean)value;
            return true;
        }
        return false;
    }

    private static class DefaultOptionsHolder {
        static final Set<SocketOption<?>> defaultOptions = defaultOptions();

        private static Set<SocketOption<?>> defaultOptions() {
            HashSet<SocketOption<?>> set = new HashSet<>();
            set.add(StandardSocketOptions.SO_SNDBUF);
            set.add(StandardSocketOptions.SO_RCVBUF);
            set.add(StandardSocketOptions.SO_KEEPALIVE);
            set.add(StandardSocketOptions.SO_REUSEADDR);
            if (Net.isReusePortAvailable()) {
                set.add(StandardSocketOptions.SO_REUSEPORT);
            }
            set.add(StandardSocketOptions.SO_LINGER);
            set.add(StandardSocketOptions.TCP_NODELAY);
            // additional options required by socket adaptor
            set.add(StandardSocketOptions.IP_TOS);
            set.add(ExtendedSocketOption.SO_OOBINLINE);
            set.addAll(ExtendedSocketOptions.clientSocketOptions());
            return Collections.unmodifiableSet(set);
        }
    }

    @Override
    public final Set<SocketOption<?>> supportedOptions() {
        return DefaultOptionsHolder.defaultOptions;
    }

    @Override
    SocketAddress bindImpl(SocketAddress local) throws IOException {
        InetSocketAddress isa = (local == null) ?
            Net.anyLocalSocketAddress(family) : Net.checkAddress(local, family);
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkListen(isa.getPort());
        }
        FileDescriptor fd = getFD();
        NetHooks.beforeTcpBind(fd, isa.getAddress(), isa.getPort());
        Net.bind(family, fd, isa.getAddress(), isa.getPort());
        return Net.localAddress(fd);
    }


    /**
     * Checks the remote address to which this channel is to be connected.
     */
    @Override
    protected InetSocketAddress checkRemote(SocketAddress sa) throws IOException {
        InetSocketAddress isa = Net.checkAddress(sa, family);
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkConnect(isa.getAddress().getHostAddress(), isa.getPort());
        }
        if (isa.getAddress().isAnyLocalAddress()) {
            if (family == Net.UNSPEC)
                return new InetSocketAddress(InetAddress.getLocalHost(), isa.getPort());
            else
                return loopbackAddressFor(isa);
        } else {
            return isa;
        }
    }

    private static InetSocketAddress loopbackAddressFor(InetSocketAddress any) {
        InetAddress anyAddr = any.getAddress();
        assert anyAddr.isAnyLocalAddress();
        InetAddress la = Net.loopBackAddressFor(anyAddr.getClass());
        return new InetSocketAddress(la, any.getPort());
    }

    @Override
    protected int connectImpl(FileDescriptor fd, SocketAddress sa) throws IOException {
        InetSocketAddress isa = (InetSocketAddress)sa;
        return Net.connect(family, fd, isa.getAddress(), isa.getPort());
    }

    @Override
    protected SocketAddress getConnectedAddress(FileDescriptor fd) throws IOException {
        return Net.localAddress(fd);
    }

    @Override
    protected String getRevealedLocalAddressAsString(SocketAddress sa) {
        InetSocketAddress isa = (InetSocketAddress)sa;
        return Net.getRevealedLocalAddressAsString(isa);
    }
}
