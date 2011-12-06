/**
 * Copyright (C) 2009-2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.restygwt.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.fusesource.restygwt.rebind.AnnotationResolver;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsonUtils;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.xml.client.Document;
import com.google.gwt.xml.client.XMLParser;
/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class Method {

    /**
     * GWT hides the full spectrum of methods because safari has a bug:
     * http://bugs.webkit.org/show_bug.cgi?id=3812
     *
     * We extend assume the server side will also check the
     * X-HTTP-Method-Override header.
     *
     * TODO: add an option to support using this approach to bypass restrictive
     * firewalls even if the browser does support the setting all the method
     * types.
     *
     * @author chirino
     */
    static private class MethodRequestBuilder extends RequestBuilder {
        public MethodRequestBuilder(String method, String url) {

            super(method, url);

            setHeader("X-HTTP-Method-Override", method);
        }
    }

    public RequestBuilder builder;

    final Set<Integer> expectedStatuses;
    {
      expectedStatuses = new HashSet<Integer>();
      expectedStatuses.add(200);
      expectedStatuses.add(201);
      expectedStatuses.add(204);
    };
    boolean anyStatus;

    Request request;
    Response response;
    Dispatcher dispatcher = Defaults.getDispatcher();

    /**
     * additional data which can be set per instance, e.g. from a {@link AnnotationResolver}
     */
    private final Map<String, String> data = new HashMap<String, String>();

    protected Method() {
    }

    public Method(Resource resource, String method) {
        builder = new MethodRequestBuilder(method, resource.getUri());
    }

    public Method user(String user) {
        builder.setUser(user);
        return this;
    }

    public Method password(String password) {
        builder.setPassword(password);
        return this;
    }

    public Method header(String header, String value) {
        builder.setHeader(header, value);
        return this;
    }

    public Method headers(Map<String, String> headers) {
        if (headers != null) {
            for (Entry<String, String> entry : headers.entrySet()) {
                builder.setHeader(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    private void doSetTimeout() {
        if (Defaults.getRequestTimeout() > -1) {
            builder.setTimeoutMillis(Defaults.getRequestTimeout());
        }
    }

    public Method text(String data) {
        defaultContentType(Resource.CONTENT_TYPE_TEXT);
        builder.setRequestData(data);
        return this;
    }

    public Method json(JSONValue data) {
        defaultContentType(Resource.CONTENT_TYPE_JSON);
        builder.setRequestData(data.toString());


        return this;
    }

    public Method xml(Document data) {
        defaultContentType(Resource.CONTENT_TYPE_XML);
        builder.setRequestData(data.toString());
        return this;
    }

    public Method timeout(int timeout) {
        builder.setTimeoutMillis(timeout);
        return this;
    }

    /**
     * sets the expected response status code.  If the response status code does not match
     * any of the values specified then the request is considered to have failed.  Defaults to accepting
     * 200,201,204. If set to -1 then any status code is considered a success.
     */
    public Method expect(int ... statuses) {
        if ( statuses.length==1 && statuses[0] < 0) {
            anyStatus = true;
        } else {
            anyStatus = false;
            this.expectedStatuses.clear();
            for( int status : statuses ) {
                this.expectedStatuses.add(status);
            }
        }
        return this;
    }

	/**
     * Local file-system (file://) does not return any status codes.
     * Therefore - if we read from the file-system we accept all codes.
     * 
     * This is for instance relevant when developing a PhoneGap application with
     * restyGwt.
     */
    public boolean isExpected(int status) {
    	
    	String baseUrl = GWT.getHostPageBaseURL();
    	String requestUrl = builder.getUrl();
		
    	if (FileSystemHelper.isRequestGoingToFileSystem(baseUrl, requestUrl)) {
    		return true;
    	} else if (anyStatus) {
            return true;
        } else {
            return this.expectedStatuses.contains(status);
        }
    }

    public void send(final RequestCallback callback) throws RequestException {
        doSetTimeout();
        builder.setCallback(callback);
        dispatcher.send(this, builder);
    }

    public void send(final TextCallback callback) {
        defaultAcceptType(Resource.CONTENT_TYPE_TEXT);
        try {
            send(new AbstractRequestCallback<String>(this, callback) {
                protected String parseResult() throws Exception {
                    return response.getText();
                }
            });
        } catch (Throwable e) {
            GWT.log("Received http error for: " + builder.getHTTPMethod() + " " + builder.getUrl(), e);
            callback.onFailure(this, e);
        }
    }

    public void send(final JsonCallback callback) {
        defaultAcceptType(Resource.CONTENT_TYPE_JSON);

        try {
            send(new AbstractRequestCallback<JSONValue>(this, callback) {
                protected JSONValue parseResult() throws Exception {
                    try {
                        return JSONParser.parseStrict(response.getText());
                    } catch (Throwable e) {
                        throw new ResponseFormatException("Response was NOT a valid JSON document", e);
                    }
                }
            });
        } catch (Throwable e) {
            GWT.log("Received http error for: " + builder.getHTTPMethod() + " " + builder.getUrl(), e);
            callback.onFailure(this, e);
        }
    }

    public void send(final XmlCallback callback) {
        defaultAcceptType(Resource.CONTENT_TYPE_XML);
        try {
            send(new AbstractRequestCallback<Document>(this, callback) {
                protected Document parseResult() throws Exception {
                    try {
                        return XMLParser.parse(response.getText());
                    } catch (Throwable e) {
                        throw new ResponseFormatException("Response was NOT a valid XML document", e);
                    }
                }
            });
        } catch (Throwable e) {
            GWT.log("Received http error for: " + builder.getHTTPMethod() + " " + builder.getUrl(), e);
            callback.onFailure(this, e);
        }
    }

    public <T extends JavaScriptObject> void send(final OverlayCallback<T> callback) {
        defaultAcceptType(Resource.CONTENT_TYPE_JSON);
        try {
            send(new AbstractRequestCallback<T>(this, callback) {
                protected T parseResult() throws Exception {
                    try {
                        return JsonUtils.safeEval(response.getText());
                    } catch (IllegalArgumentException e) {
                        throw new ResponseFormatException("Response was NOT a valid JSON document", e);
                    }
                }
            });
        } catch (Throwable e) {
            GWT.log("Received http error for: " + builder.getHTTPMethod() + " " + builder.getUrl(), e);
            callback.onFailure(this, e);
        }
    }

    public Request getRequest() {
        return request;
    }

    public Response getResponse() {
        return response;
    }

    protected void defaultContentType(String type) {
        if (builder.getHeader(Resource.HEADER_CONTENT_TYPE) == null) {
            header(Resource.HEADER_CONTENT_TYPE, type);
        }
    }

    protected void defaultAcceptType(String type) {
        if (builder.getHeader(Resource.HEADER_ACCEPT) == null) {
            header(Resource.HEADER_ACCEPT, type);
        }
    }

    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    public void setDispatcher(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * add some information onto the method which could be interesting when this method
     * comes back to the dispatcher.
     *
     * @param key
     * @param value
     */
    public void addData(String key, String value) {
        data.put(key, value);
    }

    /**
     * get all data fields which was previously added
     *
     * @return
     */
    public Map<String, String> getData() {
        return data;
    }
}
