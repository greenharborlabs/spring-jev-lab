package com.greenharborlabs.jevlab.routing;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Demo endpoint: describe an agent request, get a routing decision. Gates nothing itself. */
@RestController
public class RouteController {

  private final AgentRequestRouter router;

  public RouteController(AgentRequestRouter router) {
    this.router = router;
  }

  @PostMapping("/route")
  public RoutingDecision route(@RequestBody AgentRequest request) {
    return router.route(request);
  }
}
