/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.nio.protocol;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.entity.ContentBufferEntity;
import org.apache.http.nio.entity.ContentOutputStream;
import org.apache.http.nio.params.HttpNIOParams;
import org.apache.http.nio.protocol.ThrottlingHttpServiceHandler.ServerConnState;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.ContentInputBuffer;
import org.apache.http.nio.util.ContentOutputBuffer;
import org.apache.http.nio.util.DirectByteBufferAllocator;
import org.apache.http.nio.util.SharedInputBuffer;
import org.apache.http.nio.util.SharedOutputBuffer;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpParamsLinker;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpExecutionContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.util.concurrent.Executor;

/**
 * HTTP client handler implementation that allocates content buffers of limited 
 * size upon initialization and is capable of controlling the frequency of I/O 
 * events in order to guarantee those content buffers do not ever get overflown. 
 * This helps ensure near constant memory footprint of HTTP connections and to 
 * avoid the 'out of memory' condition while streaming out response content.
 * 
 * <p>The client handler will delegate the tasks of sending entity enclosing 
 * HTTP requests and processing of HTTP responses to an {@link Executor}, 
 * which is expected to perform those tasks using dedicated worker threads in 
 * order to avoid blocking the I/O thread.</p>
 * 
 * @see HttpNIOParams#CONTENT_BUFFER_SIZE
 * 
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 *
 */
public class ThrottlingHttpClientHandler extends NHttpClientHandlerBase {

    protected final Executor executor;
    
    public ThrottlingHttpClientHandler(
            final HttpProcessor httpProcessor, 
            final HttpRequestExecutionHandler execHandler,
            final ConnectionReuseStrategy connStrategy,
            final ByteBufferAllocator allocator,
            final Executor executor,
            final HttpParams params) {
        super(httpProcessor, execHandler, connStrategy, allocator, params);
        if (executor == null) {
            throw new IllegalArgumentException("Executor may not be null");
        }
        this.executor = executor;
    }
    
    public ThrottlingHttpClientHandler(
            final HttpProcessor httpProcessor, 
            final HttpRequestExecutionHandler execHandler,
            final ConnectionReuseStrategy connStrategy,
            final Executor executor,
            final HttpParams params) {
        this(httpProcessor, execHandler, connStrategy, 
                new DirectByteBufferAllocator(), executor, params);
    }
    
    public void connected(final NHttpClientConnection conn, final Object attachment) {
        HttpContext context = conn.getContext();

        // Populate the context with a default HTTP host based on the 
        // inet address of the target host
        if (conn instanceof HttpInetConnection) {
            InetAddress address = ((HttpInetConnection) conn).getRemoteAddress();
            int port = ((HttpInetConnection) conn).getRemotePort();
            if (address != null) {
                HttpHost host = new HttpHost(address.getHostName(), port);
                context.setAttribute(HttpExecutionContext.HTTP_TARGET_HOST, host);
            }
        }
        
        initialize(conn, attachment);
        
        int bufsize = this.params.getIntParameter(
                HttpNIOParams.CONTENT_BUFFER_SIZE, 20480);
        ClientConnState connState = new ClientConnState(bufsize, conn, this.allocator); 
        context.setAttribute(CONN_STATE, connState);

        if (this.eventListener != null) {
            this.eventListener.connectionOpen(conn);
        }
        
        requestReady(conn);        
    }

    public void closed(final NHttpClientConnection conn) {
        if (this.eventListener != null) {
            this.eventListener.connectionClosed(conn);
        }
    }

    public void requestReady(final NHttpClientConnection conn) {
        HttpContext context = conn.getContext();

        ClientConnState connState = (ClientConnState) context.getAttribute(CONN_STATE);
        
        try {

            synchronized (connState) {
                if (connState.getOutputState() != ClientConnState.READY) {
                    return;
                }

                HttpRequest request = this.execHandler.submitRequest(context);
                if (request == null) {
                    return;
                }
                
                HttpParamsLinker.link(request, this.params);
                
                context.setAttribute(HttpExecutionContext.HTTP_REQUEST, request);
                this.httpProcessor.process(request, context);
                connState.setRequest(request);
                conn.submitRequest(request);
                connState.setOutputState(ClientConnState.REQUEST_SENT);
                
                conn.requestInput();
                
                if (request instanceof HttpEntityEnclosingRequest) {
                    if (((HttpEntityEnclosingRequest) request).expectContinue()) {
                        int timeout = conn.getSocketTimeout();
                        connState.setTimeout(timeout);
                        timeout = this.params.getIntParameter(
                                HttpProtocolParams.WAIT_FOR_CONTINUE, 3000);
                        conn.setSocketTimeout(timeout);
                        connState.setOutputState(ClientConnState.EXPECT_CONTINUE);
                    } else {
                        sendRequestBody(
                                (HttpEntityEnclosingRequest) request,
                                connState,
                                conn);
                    }
                }
                
                connState.notifyAll();
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn, ex);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex, conn);
            }
        } catch (HttpException ex) {
            closeConnection(conn, ex);
            if (this.eventListener != null) {
                this.eventListener.fatalProtocolException(ex, conn);
            }
        }
    }

    public void outputReady(final NHttpClientConnection conn, final ContentEncoder encoder) {
        HttpContext context = conn.getContext();

        ClientConnState connState = (ClientConnState) context.getAttribute(CONN_STATE);

        try {

            synchronized (connState) {
                if (connState.getOutputState() == ClientConnState.EXPECT_CONTINUE) {
                    conn.suspendOutput();
                    return;
                }
                ContentOutputBuffer buffer = connState.getOutbuffer();
                buffer.produceContent(encoder);
                if (encoder.isCompleted()) {
                    connState.setInputState(ClientConnState.REQUEST_BODY_DONE);
                } else {
                    connState.setInputState(ClientConnState.REQUEST_BODY_STREAM);
                }

                connState.notifyAll();
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn, ex);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex, conn);
            }
        }
    }

    public void responseReceived(final NHttpClientConnection conn) {
        HttpContext context = conn.getContext();
        ClientConnState connState = (ClientConnState) context.getAttribute(CONN_STATE);

        try {
            
            synchronized (connState) {
                HttpResponse response = conn.getHttpResponse();
                HttpParamsLinker.link(response, this.params);
                
                HttpRequest request = connState.getRequest();
                
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode < HttpStatus.SC_OK) {
                    // 1xx intermediate response
                    if (statusCode == HttpStatus.SC_CONTINUE 
                            && connState.getOutputState() == ClientConnState.EXPECT_CONTINUE) {
                        connState.setOutputState(ClientConnState.REQUEST_SENT);
                        continueRequest(conn, connState);
                    }
                    return;
                } else {
                    connState.setResponse(response);
                    connState.setInputState(ClientConnState.RESPONSE_RECEIVED);
                    
                    if (connState.getOutputState() == ClientConnState.EXPECT_CONTINUE) {
                        int timeout = connState.getTimeout();
                        conn.setSocketTimeout(timeout);
                        conn.resetOutput();
                    }
                }
                
                if (!canResponseHaveBody(request, response)) {
                    conn.resetInput();
                    response.setEntity(null);
                    connState.setInputState(ClientConnState.RESPONSE_DONE);
                    
                    if (!this.connStrategy.keepAlive(response, context)) {
                        conn.close();
                    }
                }

                if (response.getEntity() != null) {
                    response.setEntity(new ContentBufferEntity(
                            response.getEntity(), 
                            connState.getInbuffer()));
                }
                
                context.setAttribute(HttpExecutionContext.HTTP_RESPONSE, response);
                
                this.httpProcessor.process(response, context);
                
                handleResponse(response, connState, conn);
                
                connState.notifyAll();
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn, ex);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex, conn);
            }
        } catch (HttpException ex) {
            closeConnection(conn, ex);
            if (this.eventListener != null) {
                this.eventListener.fatalProtocolException(ex, conn);
            }
        }
    }

    public void inputReady(final NHttpClientConnection conn, final ContentDecoder decoder) {
        HttpContext context = conn.getContext();

        ClientConnState connState = (ClientConnState) context.getAttribute(CONN_STATE);
        try {

            synchronized (connState) {
                HttpResponse response = connState.getResponse();
                ContentInputBuffer buffer = connState.getInbuffer();

                buffer.consumeContent(decoder);
                if (decoder.isCompleted()) {
                    connState.setInputState(ClientConnState.RESPONSE_BODY_DONE);
                    
                    if (!this.connStrategy.keepAlive(response, context)) {
                        conn.close();
                    }
                } else {
                    connState.setInputState(ClientConnState.RESPONSE_BODY_STREAM);
                }

                connState.notifyAll();
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn, ex);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex, conn);
            }
        }
    }

    public void timeout(final NHttpClientConnection conn) {
        HttpContext context = conn.getContext();
        ClientConnState connState = (ClientConnState) context.getAttribute(CONN_STATE);

        try {
            
            synchronized (connState) {
                if (connState.getOutputState() == ClientConnState.EXPECT_CONTINUE) {
                    connState.setOutputState(ClientConnState.REQUEST_SENT);
                    continueRequest(conn, connState);
                    
                    connState.notifyAll();
                }
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn, ex);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex, conn);
            }
        }
        
        closeConnection(conn, null);
        if (this.eventListener != null) {
            this.eventListener.connectionTimeout(conn);
        }
    }
    
    private void initialize(
            final NHttpClientConnection conn,
            final Object attachment) {
        HttpContext context = conn.getContext();

        context.setAttribute(HttpExecutionContext.HTTP_CONNECTION, conn);
        this.execHandler.initalizeContext(context, attachment);
    }
    
    private void continueRequest(
            final NHttpClientConnection conn, 
            final ClientConnState connState) throws IOException {

        HttpRequest request = connState.getRequest();

        int timeout = connState.getTimeout();
        conn.setSocketTimeout(timeout);

        sendRequestBody(
                (HttpEntityEnclosingRequest) request,
                connState,
                conn);
    }
    
    private void sendRequestBody(
            final HttpEntityEnclosingRequest request,
            final ClientConnState connState,
            final NHttpClientConnection conn) throws IOException {
        HttpEntity entity = request.getEntity();
        if (entity != null) {
            
            this.executor.execute(new Runnable() {
                
                public void run() {
                    try {

                        HttpEntity entity = request.getEntity();
                        OutputStream outstream = new ContentOutputStream(
                                connState.getOutbuffer());
                        entity.writeTo(outstream);
                        outstream.flush();
                        outstream.close();
                        
                    } catch (IOException ex) {
                        shutdownConnection(conn, ex);
                        if (eventListener != null) {
                            eventListener.fatalIOException(ex, conn);
                        }
                    }
                }
                
            });
        }
    }
    
    private void handleResponse(
            final HttpResponse response,
            final ClientConnState connState,
            final NHttpClientConnection conn) {

        final HttpContext context = conn.getContext();
        
        this.executor.execute(new Runnable() {
            
            public void run() {
                try {

                    execHandler.handleResponse(response, context);
                    
                    synchronized (connState) {
                        
                        try {
                            for (;;) {
                                int currentState = connState.getInputState();
                                if (currentState == ClientConnState.RESPONSE_DONE) {
                                    break;
                                }
                                if (currentState == ServerConnState.SHUTDOWN) {
                                    throw new InterruptedIOException("Service interrupted");
                                }
                                connState.wait();
                            }
                        } catch (InterruptedException ex) {
                            connState.shutdown();
                        }
                        
                        connState.resetInput();
                        connState.resetOutput();
                        if (conn.isOpen()) {
                            conn.requestOutput();
                        }
                    }
                    
                } catch (IOException ex) {
                    shutdownConnection(conn, ex);
                    if (eventListener != null) {
                        eventListener.fatalIOException(ex, conn);
                    }
                }
            }
            
        });
        
    }
    
    protected void shutdownConnection(final NHttpConnection conn, final Throwable cause) {
        HttpContext context = conn.getContext();

        ClientConnState connState = (ClientConnState) context.getAttribute(CONN_STATE);
        
        super.shutdownConnection(conn, cause);
        
        if (connState != null) {
            connState.shutdown();
        }
    }
    
    static class ClientConnState {
        
        public static final int SHUTDOWN                   = -1;
        public static final int READY                      = 0;
        public static final int REQUEST_SENT               = 1;
        public static final int EXPECT_CONTINUE            = 2;
        public static final int REQUEST_BODY_STREAM        = 4;
        public static final int REQUEST_BODY_DONE          = 8;
        public static final int RESPONSE_RECEIVED          = 16;
        public static final int RESPONSE_BODY_STREAM       = 32;
        public static final int RESPONSE_BODY_DONE         = 64;
        public static final int RESPONSE_DONE              = 64;
        
        private final SharedInputBuffer inbuffer; 
        private final SharedOutputBuffer outbuffer;

        private int inputState;
        private int outputState;
        
        private HttpRequest request;
        private HttpResponse response;

        private int timeout;
        
        public ClientConnState(
                int bufsize, 
                final IOControl ioControl, 
                final ByteBufferAllocator allocator) {
            super();
            this.inbuffer = new SharedInputBuffer(bufsize, ioControl, allocator);
            this.outbuffer = new SharedOutputBuffer(bufsize, ioControl, allocator);
            this.inputState = READY;
            this.outputState = READY;
        }

        public ContentInputBuffer getInbuffer() {
            return this.inbuffer;
        }

        public ContentOutputBuffer getOutbuffer() {
            return this.outbuffer;
        }
        
        public int getInputState() {
            return this.inputState;
        }

        public void setInputState(int inputState) {
            this.inputState = inputState;
        }

        public int getOutputState() {
            return this.outputState;
        }

        public void setOutputState(int outputState) {
            this.outputState = outputState;
        }

        public HttpRequest getRequest() {
            return this.request;
        }

        public void setRequest(final HttpRequest request) {
            this.request = request;
        }

        public HttpResponse getResponse() {
            return this.response;
        }

        public void setResponse(final HttpResponse response) {
            this.response = response;
        }

        public int getTimeout() {
            return this.timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }
            
        public void shutdown() {
            this.inbuffer.shutdown();
            this.outbuffer.shutdown();
            this.inputState = SHUTDOWN;
            this.outputState = SHUTDOWN;
        }

        public void resetInput() {
            this.inbuffer.reset();
            this.request = null;
            this.inputState = READY;
        }
        
        public void resetOutput() {
            this.outbuffer.reset();
            this.response = null;
            this.outputState = READY;
        }
        
    }
    
}