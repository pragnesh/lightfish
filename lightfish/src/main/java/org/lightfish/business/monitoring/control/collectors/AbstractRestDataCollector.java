package org.lightfish.business.monitoring.control.collectors;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientResponse.Status;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.WebResource.Builder;
import java.util.Iterator;
import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.MediaType;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.lightfish.business.monitoring.control.SessionTokenRetriever;

/**
 *
 * @author Rob Veldpaus
 */
public abstract class AbstractRestDataCollector<TYPE> implements DataCollector<TYPE> {

    protected Client client;
    @Inject
    protected Instance<String> location;
    @Inject
    protected Instance<String> sessionToken;
    @Inject
    protected SessionTokenRetriever tokenProvider;
    private String serverInstance;

    @Override
    public String getServerInstance() {
        return serverInstance;
    }

    @Override
    public void setServerInstance(String serverInstance) {
        this.serverInstance = serverInstance;
    }

    @PostConstruct
    public void initializeClient() {
        this.client = Client.create();
    }

    protected long getLong(String uri, String name) throws JSONException {
        return getLong(uri, name, "count");
    }

    protected int getInt(String uri, String name) throws JSONException {
        return getInt(uri, name, "count");
    }

    protected long getLong(String uri, String name, String key) throws JSONException {
        ClientResponse result = getClientResponse(uri);
        return getJSONObject(result, name).getLong(key);
    }

    protected int getInt(String uri, String name, String key) throws JSONException {
        ClientResponse result = getClientResponse(uri);
        return getJSONObject(result, name).getInt(key);
    }

    protected String getString(String uri, String name, String key) throws JSONException {
        ClientResponse result = getClientResponse(uri);
        return getJSONObject(result, name).getString(key);
    }

    protected String[] getStringArray(String name, String key) throws JSONException {
        String[] empty = new String[0];
        ClientResponse result = getClientResponse(name);
        JSONObject response = result.getEntity(JSONObject.class);
        response = response.optJSONObject("extraProperties");
        if (response == null) {
            return empty;
        }
        response = response.optJSONObject("childResources");
        if (response == null) {
            return empty;
        }
        int length = response.length();
        String retVal[] = new String[length];
        Iterator keys = response.keys();
        int counter = 0;
        while (keys.hasNext()) {
            retVal[counter++] = (String) keys.next();
        }
        return retVal;
    }

    protected JSONObject getJSONObject(ClientResponse result, String name) throws JSONException {
        JSONObject response = result.getEntity(JSONObject.class);
        return response.getJSONObject("extraProperties").
                getJSONObject("entity").
                getJSONObject(name);
    }

    protected String getBaseURI() {
        return getProtocol() + location.get() + "/monitoring/domain/" + serverInstance + "/";
    }

    protected String getProtocol() {
        String protocol = "http://";
        if (sessionToken != null && sessionToken.get() != null && !sessionToken.get().isEmpty()) {
            protocol = "https://";
        }
        return protocol;
    }

    protected String getLocation() {
        return location.get();
    }

    protected ClientResponse getClientResponse(String uri) throws UniformInterfaceException {
        return getClientResponse(uri, 0);
    }

    protected ClientResponse getClientResponse(String uri, int retries) throws UniformInterfaceException {
        String fullUri = getBaseURI() + uri;
        WebResource resource = client.resource(fullUri);
        Builder builder = resource.accept(MediaType.APPLICATION_JSON);
        if (sessionToken != null && sessionToken.get() != null && !sessionToken.get().isEmpty()) {
            builder.cookie(new Cookie("gfresttoken", sessionToken.get()));
        }

        try {
            return builder.get(ClientResponse.class);
        } catch (UniformInterfaceException uie) {
            if (uie.getResponse().getClientResponseStatus().equals(Status.UNAUTHORIZED)) {
                tokenProvider.retrieveSessionToken();
                if (retries < 1) {
                    return getClientResponse(uri, ++retries);
                }
            }
            throw uie;

        }
    }
}
