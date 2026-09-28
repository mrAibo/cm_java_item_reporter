package com.mraibo.cminsight.web;

import com.mraibo.cminsight.web.http.RequestContext;

/**
 * One route endpoint.
 *
 * <p>Handlers are stateless with respect to the request: everything they need is on the
 * {@link RequestContext}. The signature is also the contract for failure handling - a thrown exception
 * is caught by the router and answered with a clean JSON 500 that never carries the exception message.
 */
@FunctionalInterface
public interface RequestHandler {

    void handle(RequestContext ctx) throws Exception;
}
