package io.undertow.ajp;

import java.io.IOException;
import java.nio.ByteBuffer;

import io.undertow.UndertowLogger;
import io.undertow.UndertowOptions;
import io.undertow.channels.GatedStreamSinkChannel;
import io.undertow.server.ChannelWrapper;
import io.undertow.server.ExchangeCompleteListener;
import io.undertow.server.HttpCompletionHandler;
import io.undertow.server.HttpServerConnection;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.WorkerDispatcher;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.Pooled;
import org.xnio.channels.ChannelFactory;
import org.xnio.channels.EmptyStreamSourceChannel;
import org.xnio.channels.PushBackStreamChannel;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

import static org.xnio.IoUtils.safeClose;

/**
 * @author Stuart Douglas
 */

final class AjpReadListener implements ChannelListener<PushBackStreamChannel> {


    private static final HttpCompletionHandler COMPLETION_HANDLER = new HttpCompletionHandler() {
        @Override
        public void handleComplete() {

        }
    };

    private final StreamSinkChannel responseChannel;

    private AjpParseState state = new AjpParseState();
    private HttpServerExchange httpServerExchange;
    private final HttpServerConnection connection;

    private volatile int read = 0;
    private final int maxRequestSize;

    AjpReadListener(final StreamSinkChannel responseChannel, final PushBackStreamChannel requestChannel, final HttpServerConnection connection) {
        this.responseChannel = responseChannel;
        this.connection = connection;
        maxRequestSize = connection.getUndertowOptions().get(UndertowOptions.MAX_HEADER_SIZE, UndertowOptions.DEFAULT_MAX_HEADER_SIZE);

        httpServerExchange = new HttpServerExchange(connection, requestChannel, this.responseChannel);
        httpServerExchange.addExchangeCompleteListener(new StartNextRequestAction(requestChannel, responseChannel));
    }

    public void handleEvent(final PushBackStreamChannel channel) {
        final Pooled<ByteBuffer> pooled = connection.getBufferPool().allocate();
        final ByteBuffer buffer = pooled.getResource();
        boolean free = true;

        try {
            int res;
            do {
                buffer.clear();
                try {
                    res = channel.read(buffer);
                } catch (IOException e) {
                    if (UndertowLogger.REQUEST_LOGGER.isDebugEnabled()) {
                        UndertowLogger.REQUEST_LOGGER.debugf(e, "Connection closed with IOException");
                    }
                    safeClose(channel);
                    return;
                }
                if (res == 0) {
                    if (!channel.isReadResumed()) {
                        channel.getReadSetter().set(this);
                        channel.resumeReads();
                    }
                    return;
                }
                if (res == -1) {
                    try {
                        channel.shutdownReads();
                        final StreamSinkChannel responseChannel = this.responseChannel;
                        responseChannel.shutdownWrites();
                        // will return false if there's a response queued ahead of this one, so we'll set up a listener then
                        if (!responseChannel.flush()) {
                            responseChannel.getWriteSetter().set(ChannelListeners.flushingChannelListener(null, null));
                            responseChannel.resumeWrites();
                        }
                    } catch (IOException e) {
                        if (UndertowLogger.REQUEST_LOGGER.isDebugEnabled()) {
                            UndertowLogger.REQUEST_LOGGER.debugf(e, "Connection closed with IOException when attempting to shut down reads");
                        }
                        // fuck it, it's all ruined
                        IoUtils.safeClose(channel);
                        return;
                    }
                    return;
                }
                //TODO: we need to handle parse errors
                buffer.flip();
                int begin = buffer.remaining();
                AjpParser.INSTANCE.parse(buffer, state, httpServerExchange);
                read += (begin - buffer.remaining());
                if (buffer.hasRemaining()) {
                    free = false;
                    channel.unget(pooled);
                }
                if (read > maxRequestSize) {
                    UndertowLogger.REQUEST_LOGGER.requestHeaderWasTooLarge(connection.getPeerAddress(), maxRequestSize);
                    IoUtils.safeClose(connection);
                    return;
                }
            } while (!state.isComplete());

            // we remove ourselves as the read listener from the channel;
            // if the http handler doesn't set any then reads will suspend, which is the right thing to do
            channel.getReadSetter().set(null);
            channel.suspendReads();

            final HttpServerExchange httpServerExchange = this.httpServerExchange;
            httpServerExchange.putAttachment(UndertowOptions.ATTACHMENT_KEY, connection.getUndertowOptions());
            AjpChannelWrapper channelWrapper = new AjpChannelWrapper(new AjpResponseChannel(responseChannel, connection.getBufferPool(), httpServerExchange));
            httpServerExchange.addResponseWrapper(channelWrapper);
            httpServerExchange.addRequestWrapper(channelWrapper.getRequestWrapper());
            try {
                httpServerExchange.setRequestScheme(connection.getSslSession() != null ? "https" : "http"); //todo: determine if this is https
                state = null;
                this.httpServerExchange = null;
                connection.getRootHandler().handleRequest(httpServerExchange);

            } catch (Throwable t) {
                //TODO: we should attempt to return a 500 status code in this situation
                UndertowLogger.REQUEST_LOGGER.exceptionProcessingRequest(t);
                IoUtils.safeClose(channel);
                IoUtils.safeClose(connection);
            }
        } catch (Exception e) {
            UndertowLogger.REQUEST_LOGGER.exceptionProcessingRequest(e);
            IoUtils.safeClose(connection.getChannel());
        } finally {
            if (free) pooled.free();
        }
    }

    /**
     * Action that starts the next request
     */
    private static class StartNextRequestAction implements ExchangeCompleteListener {

        private PushBackStreamChannel requestChannel;
        private StreamSinkChannel responseChannel;


        public StartNextRequestAction(final PushBackStreamChannel requestChannel, final StreamSinkChannel responseChannel) {
            this.requestChannel = requestChannel;
            this.responseChannel = responseChannel;
        }

        @Override
        public void exchangeComplete(final HttpServerExchange exchange, final boolean isUpgrade) {

            final PushBackStreamChannel channel = this.requestChannel;
            final AjpReadListener listener = new AjpReadListener(responseChannel, channel, exchange.getConnection());
            if (channel.isReadResumed()) {
                channel.suspendReads();
            }
            WorkerDispatcher.dispatchNextRequest(channel, new DoNextRequestRead(listener, channel));
            responseChannel = null;
            this.requestChannel = null;
        }

        private static class DoNextRequestRead implements Runnable {
            private final AjpReadListener listener;
            private final PushBackStreamChannel channel;

            public DoNextRequestRead(AjpReadListener listener, PushBackStreamChannel channel) {
                this.listener = listener;
                this.channel = channel;
            }

            @Override
            public void run() {
                listener.handleEvent(channel);
            }
        }
    }

    private static class ResponseTerminateAction implements Runnable {
        private volatile GatedStreamSinkChannel nextRequestResponseChannel;
        private volatile Object permit;

        public ResponseTerminateAction(GatedStreamSinkChannel nextRequestResponseChannel, Object permit) {
            this.nextRequestResponseChannel = nextRequestResponseChannel;
            this.permit = permit;
        }

        public void run() {
            nextRequestResponseChannel.openGate(permit);
            nextRequestResponseChannel = null;
            permit = null;
        }
    }

    private class AjpChannelWrapper implements ChannelWrapper<StreamSinkChannel> {

        private final AjpResponseChannel responseChannel;

        private AjpChannelWrapper(AjpResponseChannel responseChannel) {
            this.responseChannel = responseChannel;
        }

        @Override
        public StreamSinkChannel wrap(ChannelFactory<StreamSinkChannel> channel, HttpServerExchange exchange) {
            return responseChannel;
        }

        public ChannelWrapper<StreamSourceChannel> getRequestWrapper() {
            return new ChannelWrapper<StreamSourceChannel>() {
                @Override
                public StreamSourceChannel wrap(ChannelFactory<StreamSourceChannel> channelFactory, HttpServerExchange exchange) {
                    StreamSourceChannel channel = channelFactory.create();
                    final HeaderMap requestHeaders = exchange.getRequestHeaders();
                    HttpString transferEncoding = Headers.IDENTITY;
                    Long length;
                    boolean hasTransferEncoding = requestHeaders.contains(Headers.TRANSFER_ENCODING);
                    if (hasTransferEncoding) {
                        transferEncoding = new HttpString(requestHeaders.getLast(Headers.TRANSFER_ENCODING));
                    }

                    if (hasTransferEncoding) {
                        length = null; //unkown length
                    } else if (exchange.getRequestHeaders().contains(Headers.CONTENT_LENGTH)) {
                        final long contentLength = Long.parseLong(requestHeaders.get(Headers.CONTENT_LENGTH).getFirst());
                        if (contentLength == 0L) {
                            UndertowLogger.REQUEST_LOGGER.trace("No content, starting next request");
                            // no content - immediately start the next request, returning an empty stream for this one
                            exchange.terminateRequest();
                            return new EmptyStreamSourceChannel(channel.getWorker(), channel.getReadThread());
                        } else {
                            length = contentLength;
                        }
                    } else {
                        UndertowLogger.REQUEST_LOGGER.trace("No content length or transfer coding, starting next request");
                        // no content - immediately start the next request, returning an empty stream for this one
                        exchange.terminateRequest();
                        return new EmptyStreamSourceChannel(channel.getWorker(), channel.getReadThread());
                    }
                    String contentLength = exchange.getRequestHeaders().getFirst(Headers.CONTENT_LENGTH);
                    return new AjpRequestChannel(channel, responseChannel, length);
                }
            };
        }
    }


}
