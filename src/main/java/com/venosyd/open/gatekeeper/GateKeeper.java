package com.venosyd.open.gatekeeper;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;

import com.venosyd.open.commons.http.Http;
import com.venosyd.open.commons.http.SimpleResponse;
import com.venosyd.open.commons.log.Debuggable;
import com.venosyd.open.commons.services.seeker.ServiceRegistry;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * @author sergio lisan <sels@venosyd.com>
 */
public class GateKeeper extends AbstractHandler implements Debuggable {

    /**  */
    public GateKeeper() {
    }

    /**  */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        var operationResponse = searchAndRedirect(request);

        for (var header : operationResponse.getMetadata().keySet()) {
            response.setHeader(header, (String) operationResponse.getMetadata().get(header).get(0));
        }

        if (operationResponse.getEntity() != null)
            response.getOutputStream().write((byte[]) operationResponse.getEntity());

        baseRequest.setHandled(true);
    }

    /**  */
    private Response searchAndRedirect(HttpServletRequest request) {
        try {
            var uri = new URI(request.getRequestURL().toString());
            var host = uri.getHost().replace(":8080", "");
            var origin = _searchHost(host);

            if (origin.get("status").equals("ok")) {
                var result = _redirect(origin.get("payload"), request);
                var response = ((Response.ResponseBuilder) result.get("payload")).build();

                return response;
            }

            // se a busca der um erro
            else {
                return Response.ok(origin.get("message")).build();
            }
        } catch (Exception e) {
            err.tag("INVALID REQUEST").tag("ORIGIN").ln(request.getRemoteHost());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    /**
     * funcao interna que procura por um host
     */
    private Map<String, String> _searchHost(String origin) {
        try {
            // se nao tiver, procura no service seeker

            var response = ServiceRegistry.INSTANCE.getHost(origin);

            // if nothing was found
            if (response == null) {
                var result = new HashMap<String, String>();
                result.put("status", "error");
                result.put("message", "Error 404");

                return result;
            }

            // se tiver ok
            else {
                var result = new HashMap<String, String>();
                result.put("status", "ok");
                result.put("payload", response);

                return result;
            }
        } catch (Exception e) {
            err.exception("GATEKEEPER SEARCH HOST EXCEPTION", e);

            var result = new HashMap<String, String>();
            result.put("status", "error");
            result.put("message", "Failure in requested service search");

            return result;
        }
    }

    /**
     * essa funcao redireciona a requisicao para o servico desejado com adicoes
     * encontradas em:
     * https://stackoverflow.com/questions/25247218/servlet-filter-how-to-get-all-the-headers-from-servletrequest
     */
    private Map<String, Object> _redirect(String host, HttpServletRequest request) {
        try {
            var uri = new URI(request.getRequestURL().toString());
            var url = host + uri.getPath();

            var requestHeaders = Collections.list(((HttpServletRequest) request).getHeaderNames()).stream()
                    .collect(Collectors.toMap(h -> h, request::getHeader));

            if (requestHeaders.get("If-Modified-Since") != null) {
                requestHeaders.remove("If-Modified-Since");
            }

            SimpleResponse response;

            // POST request
            if (request.getMethod().equalsIgnoreCase("POST")) {
                var content = new StringBuilder();
                try (var buffer = request.getReader()) {
                    content.append(buffer.readLine());
                }

                response = Http.post(url, content.toString(), requestHeaders);
            }

            // OPTION maybe
            else if (request.getMethod().equalsIgnoreCase("OPTION")) {
                response = Http.option(url, requestHeaders);
            }

            // GET request
            else {
                response = Http.get(url, requestHeaders);
            }

            var responseBuilder = Response.ok(response.getByteArrayBody());

            // headers do request pra response
            responseBuilder.header("Access-Control-Allow-Origin", "*");
            responseBuilder.header("Access-Control-Allow-Headers",
                    "Content-Type, Authorization, X-Requested-With, Content-Length, Accept, Origin");
            responseBuilder.header("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS");
            responseBuilder.header("Access-Control-Allow-Credentials", "true");

            for (String headerName : response.getHeaders().keySet()) {
                Object headerContent = response.getHeaders().get(headerName);
                responseBuilder = responseBuilder.header(headerName, headerContent);
            }

            // retorna resultado
            var result = new HashMap<String, Object>();
            result.put("status", "ok");
            result.put("payload", responseBuilder);

            return result;

        } catch (Exception e) {
            err.tag("INVALID REQUEST").tag("ORIGIN").ln(request.getRemoteHost());

            var result = new HashMap<String, Object>();
            result.put("status", "error");
            result.put("message", "Servico nao encontrado");

            return result;
        }
    }

}
