package com.codepurls.mailytics.api.v1.resources;

import io.dropwizard.auth.Auth;

import java.util.Collections;
import java.util.List;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.codepurls.mailytics.data.search.Request;
import com.codepurls.mailytics.data.security.User;
import com.codepurls.mailytics.service.search.SearchService;

@Produces(MediaType.APPLICATION_JSON)
public class SearchAPI {
  private static final String PARAM_QUERY        = "q";
  private static final String PARAM_PAGE         = "page";
  private static final String PARAM_SIZE         = "size";
  private static final String PARAM_DEFAULT_PAGE = "1";
  private static final String PARAM_DEFAULT_SIZE = "10";

  @Context
  private SearchService       searchService;
  @Auth
  private User                user;
  @QueryParam(PARAM_QUERY)
  private String              query;
  @DefaultValue(PARAM_DEFAULT_SIZE)
  @QueryParam(PARAM_SIZE)
  private int                 size;
  @DefaultValue(PARAM_DEFAULT_PAGE)
  @QueryParam(PARAM_PAGE)
  private int                 page;

  @GET
  public Response searchAll() {
    return Response.ok(searchService.search(user, createRequest(Collections.emptyList(), query, page, size))).build();
  }

  @GET
  @Path("mailbox")
  public Response searchMBox(@QueryParam("id") List<Integer> mbIds) {
    return Response.ok(searchService.search(user, createRequest(mbIds, query, page, size))).build();
  }

  private Request createRequest(List<Integer> mbIds, String query, int page, int size) {
    Request r = new Request();
    r.mailboxIds = mbIds;
    r.query = query;
    r.pageNum = page;
    r.pageSize = size;
    return r;
  }
}
