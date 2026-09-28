/* CM Insight console bootstrap. No framework, no CDN: everything is served from this application. */
(function () {
  "use strict";

  function setText(id, value) {
    var node = document.getElementById(id);
    if (node) {
      node.textContent = value;
    }
  }

  function getJson(path) {
    return fetch(path, { headers: { "Accept": "application/json" } }).then(function (response) {
      return response.json().then(function (body) {
        return { status: response.status, body: body };
      });
    });
  }

  getJson("/api/health").then(function (result) {
    setText("service-health", result.status === 200 && result.body.status === "UP" ? "UP" : "DEGRADED");
  }).catch(function () {
    setText("service-health", "UNREACHABLE");
  });

  getJson("/api/info").then(function (result) {
    if (result.status === 200) {
      setText("service-name", result.body.name || "CM Insight");
      setText("service-version", result.body.version || "-");
      setText("service-mode", result.body.mode || "-");
      setText("service-note", "Read-only V1/V2 access. Versions and Parts remain unavailable.");
    } else {
      setText("service-note", "Runtime details are not available (HTTP " + result.status + ").");
    }
  }).catch(function () {
    setText("service-note", "Could not reach /api/info.");
  });
})();
